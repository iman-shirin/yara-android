package com.yara.assistant

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale

data class YaraMemory(
    val id: Long,
    val project: String,
    val content: String,
    val importance: Int,
    val createdAt: Long
)

/**
 * Model-independent long-term memory. Stored locally in SQLite, separate from
 * chat transcript storage and from any LLM provider.
 */
class YaraMemoryStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext, "yara_memory.db", null, 1
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE memories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                project TEXT NOT NULL DEFAULT 'general',
                content TEXT NOT NULL,
                importance INTEGER NOT NULL DEFAULT 3,
                created_at INTEGER NOT NULL,
                normalized TEXT NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX memories_project_idx ON memories(project)")
        db.execSQL("CREATE INDEX memories_created_idx ON memories(created_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Schema migrations are added here before increasing DATABASE_VERSION.
    }

    fun save(content: String, project: String = "general", importance: Int = 3): Long {
        val clean = content.trim().replace(Regex("\\s+"), " ")
        require(clean.length in 3..2000) { "متن حافظه باید بین ۳ تا ۲۰۰۰ نویسه باشد." }
        val safeProject = project.trim().ifBlank { "general" }.take(80)
        val normalized = normalize(clean)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = db.rawQuery(
                "SELECT id FROM memories WHERE project=? AND normalized=? LIMIT 1",
                arrayOf(safeProject, normalized)
            ).use { if (it.moveToFirst()) it.getLong(0) else null }
            val now = System.currentTimeMillis()
            val id = if (existing != null) {
                db.execSQL(
                    "UPDATE memories SET content=?, importance=?, created_at=? WHERE id=?",
                    arrayOf(clean, importance.coerceIn(1, 5), now, existing)
                )
                existing
            } else {
                val values = android.content.ContentValues().apply {
                    put("project", safeProject)
                    put("content", clean)
                    put("importance", importance.coerceIn(1, 5))
                    put("created_at", now)
                    put("normalized", normalized)
                }
                db.insertOrThrow("memories", null, values)
            }
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    fun list(project: String? = null, limit: Int = 100): List<YaraMemory> {
        val db = readableDatabase
        val sql = if (project.isNullOrBlank()) {
            "SELECT id,project,content,importance,created_at FROM memories ORDER BY importance DESC, created_at DESC LIMIT ?"
        } else {
            "SELECT id,project,content,importance,created_at FROM memories WHERE project=? ORDER BY importance DESC, created_at DESC LIMIT ?"
        }
        val args = if (project.isNullOrBlank()) arrayOf(limit.coerceIn(1, 500).toString())
                   else arrayOf(project, limit.coerceIn(1, 500).toString())
        return db.rawQuery(sql, args).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    YaraMemory(
                        cursor.getLong(0), cursor.getString(1), cursor.getString(2),
                        cursor.getInt(3), cursor.getLong(4)
                    )
                )
            }
        }
    }

    fun relevant(query: String, limit: Int = 8): List<String> {
        val queryTokens = tokens(query)
        if (queryTokens.isEmpty()) return emptyList()
        return list(limit = 300).mapNotNull { memory ->
            val memoryTokens = tokens(memory.content)
            val overlap = queryTokens.intersect(memoryTokens).size
            val score = overlap.toDouble() / queryTokens.size.coerceAtLeast(1)
            if (overlap == 0) null else Triple(memory, score, overlap)
        }.sortedWith(
            compareByDescending<Triple<YaraMemory, Double, Int>> { it.second + it.first.importance * 0.025 }
                .thenByDescending { it.first.createdAt }
        ).take(limit).map { "[پروژه: ${it.first.project}] ${it.first.content}" }
    }

    fun delete(id: Long): Boolean =
        writableDatabase.delete("memories", "id=?", arrayOf(id.toString())) > 0

    fun deleteMatching(text: String, project: String? = null): Int {
        val clean = normalize(text)
        if (clean.isBlank()) return 0
        return if (project.isNullOrBlank()) {
            writableDatabase.delete("memories", "normalized LIKE ?", arrayOf("%$clean%"))
        } else {
            writableDatabase.delete(
                "memories", "project=? AND normalized LIKE ?", arrayOf(project, "%$clean%")
            )
        }
    }

    private fun tokens(value: String): Set<String> =
        normalize(value).split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 1 }.toSet()

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace('ي', 'ی').replace('ك', 'ک')
        .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
        .replace(Regex("\\s+"), " ").trim()
}
