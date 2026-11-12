package com.yara.assistant

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.Manifest
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class YaraReminder(val id: Long, val title: String, val dueAt: Long)

sealed class YaraReminderCommand {
    data class Create(val dueAt: Long, val title: String) : YaraReminderCommand()
    data object List : YaraReminderCommand()
    data class Delete(val id: Long) : YaraReminderCommand()
}

object YaraReminderCommandParser {
    private val absolutePattern = Regex("^یادآوری\\s+(\\d{4}-\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2})\\s*\\|\\s*(.+)$")
    private val relativePattern = Regex("^یادآوری\\s+(امروز|فردا)\\s+(?:ساعت\\s*)?(\\d{2}:\\d{2})\\s*\\|\\s*(.+)$")

    fun parse(input: String, now: Long = System.currentTimeMillis()): YaraReminderCommand? {
        val clean = input.trim().toEnglishDigits()
        if (clean == "یادآوری‌ها" || clean == "فهرست یادآوری‌ها") return YaraReminderCommand.List
        val delete = Regex("^حذف یادآوری\\s+(\\d+)$").matchEntire(clean)
        if (delete != null) return delete.groupValues[1].toLongOrNull()?.let(YaraReminderCommand::Delete)

        val absolute = absolutePattern.matchEntire(clean)
        val relative = relativePattern.matchEntire(clean)
        val dueAtAndTitle = runCatching {
            when {
                absolute != null -> {
                    val local = LocalDateTime.parse(
                        "${absolute.groupValues[1]} ${absolute.groupValues[2]}",
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    )
                    local to absolute.groupValues[3].trim()
                }
                relative != null -> {
                    val today = java.time.Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
                    val date = if (relative.groupValues[1] == "فردا") today.plusDays(1) else today
                    LocalDateTime.of(date, java.time.LocalTime.parse(relative.groupValues[2])) to relative.groupValues[3].trim()
                }
                else -> return null
            }
        }.getOrNull() ?: return null
        val dueAt = dueAtAndTitle.first.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (dueAt <= now || dueAtAndTitle.second.length !in 2..300) return null
        return YaraReminderCommand.Create(dueAt, dueAtAndTitle.second)
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

class YaraReminderStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext, "yara_reminders.db", null, 1
) {
    private val secrets = SecureSecretStore(context.applicationContext)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE reminders (
                id INTEGER PRIMARY KEY,
                due_at INTEGER NOT NULL,
                active INTEGER NOT NULL DEFAULT 1
            )""".trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun save(title: String, dueAt: Long): Long {
        val clean = title.trim().replace(Regex("\\s+"), " ")
        require(clean.length in 2..300) { "متن یادآوری باید بین ۲ تا ۳۰۰ نویسه باشد." }
        require(dueAt > System.currentTimeMillis()) { "زمان یادآوری باید در آینده باشد." }
        val id = System.currentTimeMillis()
        secrets.write("reminder_$id", clean)
        return try {
            writableDatabase.execSQL(
                "INSERT INTO reminders(id,due_at,active) VALUES(?,?,1)",
                arrayOf(id, dueAt)
            )
            id
        } catch (error: Exception) {
            secrets.write("reminder_$id", "")
            throw error
        }
    }

    fun get(id: Long): YaraReminder? {
        val dueAt = readableDatabase.rawQuery(
            "SELECT due_at FROM reminders WHERE id=? AND active=1",
            arrayOf(id.toString())
        ).use { if (it.moveToFirst()) it.getLong(0) else null } ?: return null
        val title = secrets.read("reminder_$id")
        if (title.isBlank()) return null
        return YaraReminder(id, title, dueAt)
    }

    fun list(): List<YaraReminder> =
        readableDatabase.rawQuery(
            "SELECT id,due_at FROM reminders WHERE active=1 ORDER BY due_at ASC", null
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val title = secrets.read("reminder_$id")
                    if (title.isNotBlank()) add(YaraReminder(id, title, cursor.getLong(1)))
                }
            }
        }

    fun delete(id: Long): Boolean {
        secrets.write("reminder_$id", "")
        return writableDatabase.delete("reminders", "id=?", arrayOf(id.toString())) > 0
    }

    fun markFired(id: Long) {
        writableDatabase.execSQL("UPDATE reminders SET active=0 WHERE id=?", arrayOf(id))
        secrets.write("reminder_$id", "")
    }
}

class YaraReminderScheduler(private val context: Context) {
    fun schedule(reminder: YaraReminder) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            reminder.dueAt,
            pendingIntent(reminder.id)
        )
    }

    fun cancel(id: Long) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.cancel(pendingIntent(id))
    }

    private fun pendingIntent(id: Long): PendingIntent {
        val intent = Intent(context, YaraReminderReceiver::class.java)
            .putExtra(EXTRA_REMINDER_ID, id)
        return PendingIntent.getBroadcast(
            context, id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val EXTRA_REMINDER_ID = "yara.reminder.id"
        const val CHANNEL_ID = "yara_reminders"
    }
}

class YaraReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(YaraReminderScheduler.EXTRA_REMINDER_ID, -1L)
        if (id <= 0) return
        val store = YaraReminderStore(context)
        val reminder = store.get(id) ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    YaraReminderScheduler.CHANNEL_ID,
                    "یادآوری‌های یارا",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "اعلان یادآوری‌های ثبت‌شده در یارا" }
            )
        }
        val openApp = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, YaraReminderScheduler.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("یادآوری یارا")
            .setContentText(reminder.title)
            .setStyle(Notification.BigTextStyle().bigText(reminder.title))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        manager.notify(id.hashCode(), notification)
        store.markFired(id)
    }
}

class YaraBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val scheduler = YaraReminderScheduler(context)
        YaraReminderStore(context).list().forEach(scheduler::schedule)
    }
}
