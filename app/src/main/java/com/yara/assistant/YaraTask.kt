package com.yara.assistant

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class YaraTask(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val completedAt: Long?
)

sealed class YaraTaskCommand {
    data class Add(val title: String) : YaraTaskCommand()
    data object ListPending : YaraTaskCommand()
    data object ListCompleted : YaraTaskCommand()
    data class Complete(val id: Long) : YaraTaskCommand()
    data class Delete(val id: Long) : YaraTaskCommand()
}

object YaraTaskCommandParser {
    fun parse(input: String): YaraTaskCommand? {
        val clean = input.trim().toEnglishDigits()
        val add = Regex("^(?:کار جدید|وظیفه جدید)\\s*[:：]\\s*(.+)$").matchEntire(clean)
        if (add != null) return add.groupValues[1].trim()
            .takeIf { it.length in 2..300 }?.let(YaraTaskCommand::Add)
        if (clean == "کارها" || clean == "فهرست کارها" || clean == "وظایف") {
            return YaraTaskCommand.ListPending
        }
        if (clean == "کارهای انجام‌شده" || clean == "کارهای تکمیل‌شده") {
            return YaraTaskCommand.ListCompleted
        }
        val complete = Regex("^(?:انجام شد|تکمیل کار)\\s+(\\d+)$").matchEntire(clean)
        if (complete != null) return complete.groupValues[1].toLongOrNull()?.let(YaraTaskCommand::Complete)
        val delete = Regex("^حذف کار\\s+(\\d+)$").matchEntire(clean)
        if (delete != null) return delete.groupValues[1].toLongOrNull()?.let(YaraTaskCommand::Delete)
        return null
    }

    private fun String.toEnglishDigits(): String {
        val persian = "۰۱۲۳۴۵۶۷۸۹"
        val arabic = "٠١٢٣٤٥٦٧٨٩"
        return map { ch ->
            val p = persian.indexOf(ch)
            val a = arabic.indexOf(ch)
            when { p >= 0 -> '0' + p; a >= 0 -> '0' + a; else -> ch }
        }.joinToString("")
    }
}

class YaraTaskStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext, "yara_tasks.db", null, 1
) {
    private val secrets = SecureSecretStore(context.applicationContext)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE tasks (
                id INTEGER PRIMARY KEY,
                created_at INTEGER NOT NULL,
                completed_at INTEGER
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX tasks_created_idx ON tasks(created_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun add(title: String): Long {
        val clean = title.trim().replace(Regex("\\s+"), " ")
        require(clean.length in 2..300) { "عنوان کار باید بین ۲ تا ۳۰۰ نویسه باشد." }
        val id = System.currentTimeMillis()
        secrets.write("task_$id", clean)
        return try {
            writableDatabase.execSQL(
                "INSERT INTO tasks(id,created_at,completed_at) VALUES(?,?,NULL)",
                arrayOf(id, System.currentTimeMillis())
            )
            id
        } catch (error: Exception) {
            secrets.write("task_$id", "")
            throw error
        }
    }

    fun list(completed: Boolean = false, limit: Int = 100): List<YaraTask> {
        val where = if (completed) "completed_at IS NOT NULL" else "completed_at IS NULL"
        return readableDatabase.rawQuery(
            "SELECT id,created_at,completed_at FROM tasks WHERE $where ORDER BY created_at DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, 500).toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val title = secrets.read("task_$id")
                    if (title.isNotBlank()) {
                        add(YaraTask(id, title, cursor.getLong(1), cursor.getLong(2).takeIf { !cursor.isNull(2) }))
                    }
                }
            }
        }
    }

    fun complete(id: Long): Boolean {
        val changed = writableDatabase.update(
            "tasks",
            android.content.ContentValues().apply { put("completed_at", System.currentTimeMillis()) },
            "id=? AND completed_at IS NULL",
            arrayOf(id.toString())
        )
        return changed > 0
    }

    fun delete(id: Long): Boolean {
        secrets.write("task_$id", "")
        return writableDatabase.delete("tasks", "id=?", arrayOf(id.toString())) > 0
    }
}
