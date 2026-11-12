package com.yara.assistant

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class YaraMemory(
    val id: Long,
    val project: String,
    val content: String,
    val importance: Int,
    val createdAt: Long
)

/**
 * Model-independent long-term memory.
 * All user text and project labels are AES-256-GCM encrypted at rest with an
 * Android Keystore key. Search decrypts records in-process; no plaintext
 * content index is written to SQLite.
 */
class YaraMemoryStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext, "yara_memory.db", null, 1
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE memories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                project_enc TEXT NOT NULL,
                content_enc TEXT NOT NULL,
                importance INTEGER NOT NULL DEFAULT 3,
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX memories_created_idx ON memories(created_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Add explicit migrations here before increasing DATABASE_VERSION.
    }

    fun save(content: String, project: String = "general", importance: Int = 3): Long {
        val clean = content.trim().replace(Regex("\\s+"), " ")
        require(clean.length in 3..2000) { "متن حافظه باید بین ۳ تا ۲۰۰۰ نویسه باشد." }
        val safeProject = project.trim().ifBlank { "general" }.take(80)
        val existing = list().firstOrNull {
            it.project == safeProject && normalize(it.content) == normalize(clean)
        }
        val now = System.currentTimeMillis()
        if (existing != null) {
            writableDatabase.execSQL(
                "UPDATE memories SET content_enc=?, importance=?, created_at=? WHERE id=?",
                arrayOf(encrypt(clean), importance.coerceIn(1, 5), now, existing.id)
            )
            return existing.id
        }
        val values = android.content.ContentValues().apply {
            put("project_enc", encrypt(safeProject))
            put("content_enc", encrypt(clean))
            put("importance", importance.coerceIn(1, 5))
            put("created_at", now)
        }
        return writableDatabase.insertOrThrow("memories", null, values)
    }

    fun list(project: String? = null, limit: Int = 100): List<YaraMemory> {
        return readableDatabase.rawQuery(
            "SELECT id,project_enc,content_enc,importance,created_at FROM memories ORDER BY importance DESC, created_at DESC",
            null
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext() && size < limit.coerceIn(1, 500)) {
                    val record = runCatching {
                        YaraMemory(
                            cursor.getLong(0), decrypt(cursor.getString(1)),
                            decrypt(cursor.getString(2)), cursor.getInt(3), cursor.getLong(4)
                        )
                    }.getOrNull() ?: continue
                    if (project.isNullOrBlank() || record.project == project) add(record)
                }
            }
        }
    }

    fun relevant(query: String, limit: Int = 8): List<String> {
        val queryTokens = tokens(query)
        if (queryTokens.isEmpty()) return emptyList()
        return list(limit = 500).mapNotNull { memory ->
            val overlap = queryTokens.intersect(tokens(memory.content)).size
            if (overlap == 0) null else Triple(
                memory,
                overlap.toDouble() / queryTokens.size.coerceAtLeast(1),
                overlap
            )
        }.sortedWith(
            compareByDescending<Triple<YaraMemory, Double, Int>> { it.second + it.first.importance * 0.025 }
                .thenByDescending { it.first.createdAt }
        ).take(limit).map { "[پروژه: ${it.first.project}] ${it.first.content}" }
    }

    fun delete(id: Long): Boolean =
        writableDatabase.delete("memories", "id=?", arrayOf(id.toString())) > 0

    fun deleteMatching(text: String, project: String? = null): Int {
        val needle = normalize(text)
        if (needle.isBlank()) return 0
        val ids = list(limit = 500).filter {
            (project.isNullOrBlank() || it.project == project) &&
                normalize(it.content).contains(needle)
        }.map { it.id }
        return ids.count { delete(it) }
    }

    private fun tokens(value: String): Set<String> =
        normalize(value).split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 1 }.toSet()

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace('ي', 'ی').replace('ك', 'ک')
        .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
        .replace(Regex("\\s+"), " ").trim()

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val parts = value.split(':', limit = 2)
        require(parts.size == 2) { "Encrypted memory record is malformed." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE, getOrCreateKey(),
            GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
        )
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "yara_memory_aes"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
