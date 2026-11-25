package com.yara.assistant

import android.content.Context
import android.util.AtomicFile
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ChatLine(val role: String, val content: String)

data class ChatConversation(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messages: List<ChatLine>
)

/** Compatibility facade for the original single-conversation call sites. */
class ChatStore(context: Context) {
    private val encrypted = EncryptedChatStore(context)

    fun load(): List<ChatLine> = encrypted.load()
    fun save(lines: List<ChatLine>) = encrypted.save(lines)

    fun newConversation(): ChatConversation = encrypted.newConversation()
    fun loadConversations(): List<ChatConversation> = encrypted.loadConversations()
    fun loadActiveConversationId(): String? = encrypted.loadActiveConversationId()
    fun saveActiveConversationId(id: String) = encrypted.saveActiveConversationId(id)
    fun saveConversation(conversation: ChatConversation) = encrypted.saveConversation(conversation)
}

internal class EncryptedChatStore(context: Context) {
    private val appContext = context.applicationContext
    private val legacyPreferences = appContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
    private val archiveFile = AtomicFile(File(appContext.noBackupFilesDir, ARCHIVE_NAME))
    private val archiveBackupDirectory = File(appContext.noBackupFilesDir, BACKUP_DIRECTORY).apply { mkdirs() }
    private var loadedSuccessfully = false
    private var writesBlocked = false
    private var loadedConversations: List<ChatConversation>? = null

    @Synchronized
    fun newConversation(): ChatConversation = ChatConversation(
        id = UUID.randomUUID().toString(),
        title = NEW_CHAT_TITLE,
        updatedAt = System.currentTimeMillis(),
        messages = emptyList()
    )

    @Synchronized
    fun loadActiveConversationId(): String? = legacyPreferences.getString(KEY_ACTIVE_CONVERSATION, null)

    @Synchronized
    fun saveActiveConversationId(id: String) {
        require(id.isNotBlank()) { "شناسه گفتگوی فعال معتبر نیست." }
        check(legacyPreferences.edit().putString(KEY_ACTIVE_CONVERSATION, id).commit()) {
            "ذخیره گفتگوی فعال انجام نشد."
        }
    }

    /** Loads the complete archive. Corruption and key loss fail closed. */
    @Synchronized
    fun loadConversations(): List<ChatConversation> {
        loadedSuccessfully = false
        writesBlocked = false
        loadedConversations = null

        if (hasArchive()) {
            val key = existingKeyOrNull()
            if (key == null) {
                preserveArchive()
                writesBlocked = true
                throw ChatStoreException("کلید تاریخچه در دسترس نیست؛ فایل حفظ شده و نوشتن متوقف شد.")
            }
            val payload = try {
                EncryptedChatCrypto.decrypt(readArchiveBytes(), key)
            } catch (error: Exception) {
                preserveArchive()
                writesBlocked = true
                throw ChatStoreException("آرشیو تاریخچه قابل‌بازیابی نیست؛ نسخهٔ خراب حفظ شد و نوشتن متوقف شد.", error)
            }
            val decoded = try {
                ChatArchiveCodec.decodeConversations(payload)
            } catch (error: Exception) {
                preserveArchive()
                writesBlocked = true
                throw ChatStoreException("ساختار آرشیو تاریخچه معتبر نیست؛ نسخهٔ خراب حفظ شد و نوشتن متوقف شد.", error)
            }

            // Upgrade the previous encrypted single-chat format only after a recoverable copy exists.
            if (ChatArchiveCodec.version(payload) == ChatArchiveCodec.VERSION_SINGLE_CHAT) {
                preserveArchive()
                try {
                    writeArchive(decoded, key)
                    check(ChatArchiveCodec.decodeConversations(
                        EncryptedChatCrypto.decrypt(readArchiveBytes(), key)
                    ) == decoded) { "Encrypted conversation migration verification failed" }
                } catch (error: Exception) {
                    writesBlocked = true
                    throw ChatStoreException("ارتقای آرشیو رمزنگاری‌شده تأیید نشد؛ نسخهٔ قبلی حفظ شد و نوشتن متوقف شد.", error)
                }
            }
            loadedConversations = decoded
            loadedSuccessfully = true
            return decoded
        }

        val conversationsRaw = legacyPreferences.getString(KEY_CONVERSATIONS, null)
        val legacyRaw = legacyPreferences.getString(LEGACY_KEY, null)
        if (conversationsRaw == null && legacyRaw == null) {
            loadedConversations = emptyList()
            loadedSuccessfully = true
            return emptyList()
        }

        val key = existingKeyOrCreate()
        val sourceRaw = conversationsRaw ?: legacyRaw!!
        preservePlaintextSource(sourceRaw, key)
        val migrated = try {
            if (conversationsRaw != null) parseLegacyConversations(conversationsRaw)
            else {
                val lines = parseLegacy(legacyRaw!!)
                if (lines.isEmpty()) emptyList() else listOf(conversationFrom(lines))
            }
        } catch (error: Exception) {
            writesBlocked = true
            throw ChatStoreException("تاریخچهٔ قدیمی خراب است؛ نسخهٔ پشتیبان حفظ شد و نوشتن متوقف شد.", error)
        }
        try {
            writeArchive(migrated, key)
            val verified = ChatArchiveCodec.decodeConversations(
                EncryptedChatCrypto.decrypt(readArchiveBytes(), key)
            )
            check(verified == migrated) { "Encrypted chat migration verification failed" }
            check(legacyPreferences.edit().remove(LEGACY_KEY).remove(KEY_CONVERSATIONS).commit()) {
                "Legacy plaintext history could not be removed"
            }
        } catch (error: Exception) {
            writesBlocked = true
            throw ChatStoreException("مهاجرت تاریخچه تأیید نشد؛ نسخهٔ قدیمی حفظ شد و نوشتن متوقف شد.", error)
        }
        if (migrated.isNotEmpty() && loadActiveConversationId() == null) {
            saveActiveConversationId(migrated.first().id)
        }
        loadedConversations = migrated
        loadedSuccessfully = true
        return migrated
    }

    /** Original API: reads the selected conversation, or the newest one. */
    @Synchronized
    fun load(): List<ChatLine> {
        val conversations = loadConversations()
        val activeId = loadActiveConversationId()
        return conversations.firstOrNull { it.id == activeId }?.messages
            ?: conversations.maxByOrNull { it.updatedAt }?.messages
            ?: emptyList()
    }

    @Synchronized
    fun saveConversation(conversation: ChatConversation) {
        require(conversation.id.isNotBlank()) { "شناسه گفتگو معتبر نیست." }
        require(conversation.messages.size <= ChatArchiveCodec.MAX_MESSAGES_PER_CONVERSATION) {
            "تعداد پیام‌های گفتگو بیش از حد مجاز است."
        }
        conversation.messages.forEach { line ->
            require(line.role in ALLOWED_ROLES && line.content.isNotBlank()) { "Invalid chat message" }
        }
        check(loadedSuccessfully && !writesBlocked) {
            "تاریخچه ابتدا باید با موفقیت خوانده شود؛ تا آن زمان نوشتن برای جلوگیری از حذف داده متوقف است."
        }
        val key = archiveKeyForWrite()
        val current = loadedConversations.orEmpty()
        val messages = conversation.messages.takeLast(ChatArchiveCodec.MAX_MESSAGES_PER_CONVERSATION)
        val title = titleFor(messages).takeIf { messages.isNotEmpty() } ?:
            current.firstOrNull { it.id == conversation.id }?.title ?: NEW_CHAT_TITLE
        val saved = conversation.copy(title = title, updatedAt = System.currentTimeMillis(), messages = messages)
        val updated = (current.filterNot { it.id == saved.id } + saved)
            .sortedByDescending { it.updatedAt }
        require(updated.size <= ChatArchiveCodec.MAX_CONVERSATIONS) { "تعداد گفتگوها به سقف مجاز رسیده است." }
        persist(updated, key)
    }

    /** Original API: updates the active conversation while preserving the rest. */
    @Synchronized
    fun save(lines: List<ChatLine>) {
        check(loadedSuccessfully && !writesBlocked) {
            "تاریخچه ابتدا باید با موفقیت خوانده شود؛ تا آن زمان نوشتن برای جلوگیری از حذف داده متوقف است."
        }
        require(lines.size <= ChatArchiveCodec.MAX_MESSAGES_PER_CONVERSATION) {
            "تعداد پیام‌های گفتگو بیش از حد مجاز است."
        }
        lines.forEach { require(it.role in ALLOWED_ROLES && it.content.isNotBlank()) { "Invalid chat message" } }
        val key = archiveKeyForWrite()
        val current = loadedConversations.orEmpty()
        val activeId = loadActiveConversationId()
        val selected = current.firstOrNull { it.id == activeId }
            ?: current.maxByOrNull { it.updatedAt }
        val id = selected?.id ?: activeId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val updatedConversation = ChatConversation(
            id = id,
            title = titleFor(lines).takeIf { lines.isNotEmpty() } ?: selected?.title ?: NEW_CHAT_TITLE,
            updatedAt = System.currentTimeMillis(),
            messages = lines.takeLast(ChatArchiveCodec.MAX_MESSAGES_PER_CONVERSATION)
        )
        val updated = (current.filterNot { it.id == id } + updatedConversation)
            .sortedByDescending { it.updatedAt }
        require(updated.size <= ChatArchiveCodec.MAX_CONVERSATIONS) { "تعداد گفتگوها به سقف مجاز رسیده است." }
        persist(updated, key)
        if (loadActiveConversationId() == null) saveActiveConversationId(id)
    }

    private fun archiveKeyForWrite(): SecretKey {
        val key = if (hasArchive()) existingKeyOrNull() else existingKeyOrCreate()
        if (key == null) {
            preserveArchive()
            writesBlocked = true
            loadedSuccessfully = false
            throw ChatStoreException("کلید تاریخچه از دست رفته است؛ آرشیو حفظ شد و نوشتن متوقف شد.")
        }
        return key
    }

    private fun persist(conversations: List<ChatConversation>, key: SecretKey) {
        try {
            writeArchive(conversations, key)
            val verified = ChatArchiveCodec.decodeConversations(
                EncryptedChatCrypto.decrypt(readArchiveBytes(), key)
            )
            check(verified == conversations) { "Encrypted conversation archive verification failed" }
            loadedConversations = conversations
        } catch (error: Exception) {
            writesBlocked = true
            loadedSuccessfully = false
            throw ChatStoreException("ذخیرهٔ رمزنگاری‌شدهٔ تاریخچه ناموفق بود.", error)
        }
    }

    private fun conversationFrom(lines: List<ChatLine>) = ChatConversation(
        id = UUID.randomUUID().toString(),
        title = titleFor(lines),
        updatedAt = System.currentTimeMillis(),
        messages = lines.takeLast(ChatArchiveCodec.MAX_MESSAGES_PER_CONVERSATION)
    )

    private fun readArchiveBytes(): ByteArray = archiveFile.openRead().use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= EncryptedChatCrypto.MAX_ENVELOPE_BYTES) { "Encrypted archive is too large" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

    private fun hasArchive(): Boolean =
        archiveFile.baseFile.exists() || File(archiveFile.baseFile.path + ".bak").exists()

    private fun writeArchive(conversations: List<ChatConversation>, key: SecretKey) {
        val encrypted = EncryptedChatCrypto.encrypt(ChatArchiveCodec.encodeConversations(conversations), key)
        val stream = archiveFile.startWrite()
        try {
            stream.write(encrypted)
            archiveFile.finishWrite(stream)
        } catch (error: Exception) {
            archiveFile.failWrite(stream)
            throw error
        }
    }

    private fun parseLegacy(raw: String): List<ChatLine> {
        val array = JSONArray(raw)
        require(array.length() <= MAX_LEGACY_MESSAGES) { "Legacy archive is too large" }
        val decoded = ArrayList<ChatLine>(array.length())
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val role = item.getString("role")
            val content = item.getString("content")
            require(role in ALLOWED_ROLES && content.isNotBlank()) { "Legacy archive contains an invalid record" }
            decoded += ChatLine(role, content)
        }
        require(decoded.size <= ChatArchiveCodec.MAX_MESSAGES_PER_CONVERSATION) {
            "Legacy archive exceeds the supported message count"
        }
        return decoded
    }

    private fun parseLegacyConversations(raw: String): List<ChatConversation> {
        val array = JSONArray(raw)
        require(array.length() <= ChatArchiveCodec.MAX_CONVERSATIONS) { "Too many legacy conversations" }
        val decoded = ArrayList<ChatConversation>(array.length())
        val ids = HashSet<String>()
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val id = item.getString("id")
            require(id.isNotBlank() && ids.add(id)) { "Invalid or duplicate legacy conversation ID" }
            val title = item.optString("title", NEW_CHAT_TITLE).ifBlank { NEW_CHAT_TITLE }
            val updatedAt = item.optLong("updatedAt", 0L)
            require(updatedAt >= 0L) { "Invalid legacy conversation timestamp" }
            val messagesArray = item.getJSONArray("messages")
            require(messagesArray.length() <= ChatArchiveCodec.MAX_MESSAGES_PER_CONVERSATION) {
                "Legacy conversation exceeds the supported message count"
            }
            val messages = ArrayList<ChatLine>(messagesArray.length())
            for (messageIndex in 0 until messagesArray.length()) {
                val message = messagesArray.getJSONObject(messageIndex)
                val role = message.getString("role")
                val content = message.getString("content")
                require(role in ALLOWED_ROLES && content.isNotBlank()) { "Legacy conversation contains an invalid record" }
                messages += ChatLine(role, content)
            }
            decoded += ChatConversation(id, title, updatedAt, messages)
        }
        return decoded.sortedByDescending { it.updatedAt }
    }

    private fun preservePlaintextSource(raw: String, key: SecretKey) {
        val bytes = raw.toByteArray(Charsets.UTF_8)
        val destination = AtomicFile(
            File(appContext.noBackupFilesDir, "chat-migration-${fingerprint(bytes)}.enc.bak")
        )
        if (!destination.baseFile.exists() && !File(destination.baseFile.path + ".bak").exists()) {
            val stream = destination.startWrite()
            try {
                stream.write(EncryptedChatCrypto.encrypt(bytes, key))
                destination.finishWrite(stream)
            } catch (error: Exception) {
                destination.failWrite(stream)
                throw ChatStoreException("پشتیبان رمزنگاری‌شدهٔ تاریخچهٔ قدیمی ساخته نشد.", error)
            }
        }
        val backupPlaintext = EncryptedChatCrypto.decrypt(destination.openRead().use { it.readBytes() }, key)
        check(MessageDigest.isEqual(bytes, backupPlaintext)) {
            "پشتیبان رمزنگاری‌شدهٔ تاریخچهٔ قدیمی تأیید نشد."
        }
    }

    private fun preserveArchive() {
        if (!hasArchive()) return
        val bytes = archiveFile.openRead().use { it.readBytes() }
        val destination = AtomicFile(File(archiveBackupDirectory, "chat-archived-${fingerprint(bytes)}.enc.bak"))
        if (!destination.baseFile.exists() && !File(destination.baseFile.path + ".bak").exists()) {
            val stream = destination.startWrite()
            try {
                stream.write(bytes)
                destination.finishWrite(stream)
            } catch (error: Exception) {
                destination.failWrite(stream)
                throw error
            }
        }
        val backup = destination.openRead().use { it.readBytes() }
        check(MessageDigest.isEqual(bytes, backup)) { "Chat archive recovery backup verification failed" }
    }

    private fun fingerprint(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun existingKeyOrCreate(): SecretKey = existingKeyOrNull() ?: createKey()

    private fun existingKeyOrNull(): SecretKey? = runCatching {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    }.getOrNull()

    private fun createKey(): SecretKey {
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

    class ChatStoreException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "yara_chat_archive_aes_v1"
        const val LEGACY_PREFS = "yara_chat"
        const val LEGACY_KEY = "lines"
        const val KEY_ACTIVE_CONVERSATION = "active_conversation_id"
        const val ARCHIVE_NAME = "yara_chat_history.enc"
        const val BACKUP_DIRECTORY = "yara_chat_recovery"
        const val MAX_LEGACY_MESSAGES = 10000
        const val NEW_CHAT_TITLE = "گفتگوی جدید"
        const val MAX_TITLE_LENGTH = 48
        val ALLOWED_ROLES = setOf("user", "assistant")
    }
}

internal object EncryptedChatCrypto {
    private val magic = byteArrayOf(0x59, 0x43, 0x45, 0x31)

    fun encrypt(plain: ByteArray, key: SecretKey): ByteArray {
        require(plain.size <= MAX_PLAINTEXT_BYTES) { "Chat archive is too large" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        require(iv.size == 12) { "Unexpected AES-GCM IV size" }
        cipher.updateAAD(aad())
        val ciphertext = cipher.doFinal(plain)
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(magic)
                output.writeInt(1)
                output.writeByte(iv.size)
                output.write(iv)
                output.write(ciphertext)
            }
            bytes.toByteArray()
        }
    }

    fun decrypt(envelope: ByteArray, key: SecretKey): ByteArray {
        require(envelope.size in MIN_ENVELOPE_BYTES..MAX_ENVELOPE_BYTES) { "Invalid encrypted archive size" }
        DataInputStream(ByteArrayInputStream(envelope)).use { input ->
            val actualMagic = ByteArray(magic.size).also(input::readFully)
            require(actualMagic.contentEquals(magic)) { "Invalid encrypted archive header" }
            require(input.readInt() == 1) { "Unsupported encrypted archive version" }
            val ivLength = input.readUnsignedByte()
            require(ivLength == 12) { "Invalid encrypted archive IV" }
            val iv = ByteArray(ivLength).also(input::readFully)
            val remaining = envelope.size - magic.size - Int.SIZE_BYTES - 1 - ivLength
            require(remaining >= 16) { "Truncated encrypted archive" }
            val ciphertext = ByteArray(remaining).also(input::readFully)
            require(input.available() == 0) { "Unexpected encrypted archive data" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(aad())
            return cipher.doFinal(ciphertext)
        }
    }

    private fun aad() = magic + byteArrayOf(0, 0, 0, 1)
    const val MAX_PLAINTEXT_BYTES = 8 * 1024 * 1024 - 37
    const val MAX_ENVELOPE_BYTES = 8 * 1024 * 1024
    private const val MIN_ENVELOPE_BYTES = 4 + 4 + 1 + 12 + 16
}

internal object ChatArchiveCodec {
    const val MAX_MESSAGES_PER_CONVERSATION = 500
    const val MAX_CONVERSATIONS = 200
    private const val MAGIC = 0x59434854 // YCHT
    private const val VERSION = 2
    const val VERSION_SINGLE_CHAT = 1
    private const val MAX_MESSAGE_BYTES = 1_000_000
    private const val MAX_ID_BYTES = 256
    private const val MAX_TITLE_BYTES = 256

    /** Version-one single-chat helpers retained for backwards-compatibility tests. */
    fun encode(lines: List<ChatLine>): ByteArray = ChatArchiveCodecTestCompat.encode(lines)
    fun decode(payload: ByteArray): List<ChatLine> = ChatArchiveCodecV1.decode(payload)

    /** Version 2 encoder for encrypted multi-conversation archives. */
    fun encodeConversations(conversations: List<ChatConversation>): ByteArray {
        require(conversations.size <= MAX_CONVERSATIONS) { "Too many conversations" }
        require(conversations.map { it.id }.distinct().size == conversations.size) { "Duplicate conversation ID" }
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeInt(conversations.size)
                for (conversation in conversations) {
                    require(conversation.id.isNotBlank()) { "Invalid conversation ID" }
                    require(conversation.updatedAt >= 0) { "Invalid conversation timestamp" }
                    require(conversation.messages.size <= MAX_MESSAGES_PER_CONVERSATION) { "Too many messages" }
                    writeString(output, conversation.id, MAX_ID_BYTES)
                    writeString(output, conversation.title, MAX_TITLE_BYTES)
                    output.writeLong(conversation.updatedAt)
                    output.writeInt(conversation.messages.size)
                    for (line in conversation.messages) {
                        require(line.role == "user" || line.role == "assistant") { "Invalid message role" }
                        require(line.content.isNotBlank()) { "Empty message" }
                        writeString(output, line.role, 16)
                        writeString(output, line.content, MAX_MESSAGE_BYTES)
                    }
                }
                require(bytes.size() <= EncryptedChatCrypto.MAX_PLAINTEXT_BYTES) { "Chat archive is too large" }
            }
            bytes.toByteArray()
        }
    }

    fun decodeConversations(payload: ByteArray): List<ChatConversation> {
        require(payload.size <= EncryptedChatCrypto.MAX_PLAINTEXT_BYTES) { "Chat archive is too large" }
        return when (version(payload)) {
            VERSION_SINGLE_CHAT -> listOf(
                ChatConversation(
                    id = UUID.randomUUID().toString(),
                    title = titleFor(ChatArchiveCodecV1.decode(payload)),
                    updatedAt = System.currentTimeMillis(),
                    messages = ChatArchiveCodecV1.decode(payload)
                )
            )
            VERSION -> decodeV2(payload)
            else -> throw IllegalArgumentException("Unsupported chat archive version")
        }
    }

    fun version(payload: ByteArray): Int {
        require(payload.size >= 8) { "Truncated chat archive" }
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == MAGIC) { "Invalid chat archive header" }
            return input.readInt()
        }
    }

    private fun decodeV2(payload: ByteArray): List<ChatConversation> {
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == MAGIC) { "Invalid chat archive header" }
            require(input.readInt() == VERSION) { "Unsupported chat archive version" }
            val count = input.readInt()
            require(count in 0..MAX_CONVERSATIONS) { "Invalid conversation count" }
            val ids = HashSet<String>(count)
            val conversations = ArrayList<ChatConversation>(count)
            repeat(count) {
                val id = readString(input, MAX_ID_BYTES).also { require(it.isNotBlank()) }
                require(ids.add(id)) { "Duplicate conversation ID" }
                val title = readString(input, MAX_TITLE_BYTES)
                val updatedAt = input.readLong().also { require(it >= 0) }
                val messageCount = input.readInt()
                require(messageCount in 0..MAX_MESSAGES_PER_CONVERSATION) { "Invalid message count" }
                val messages = ArrayList<ChatLine>(messageCount)
                repeat(messageCount) {
                    val role = readString(input, 16)
                    require(role == "user" || role == "assistant") { "Invalid message role" }
                    val content = readString(input, MAX_MESSAGE_BYTES)
                    require(content.isNotBlank()) { "Empty message" }
                    messages += ChatLine(role, content)
                }
                conversations += ChatConversation(id, title, updatedAt, messages)
            }
            require(input.available() == 0) { "Trailing chat archive data" }
            return conversations.sortedByDescending { it.updatedAt }
        }
    }

    private fun writeString(output: DataOutputStream, value: String, maxBytes: Int) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxBytes) { "Chat archive string is too large" }
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readString(input: DataInputStream, maxBytes: Int): String {
        val size = input.readInt()
        require(size in 1..maxBytes) { "Invalid chat archive string length" }
        return ByteArray(size).also(input::readFully).toString(Charsets.UTF_8)
    }

    private fun titleFor(messages: List<ChatLine>): String = messages.firstOrNull { it.role == "user" }
        ?.content?.replace(Regex("\\s+"), " ")?.take(48)?.takeIf { it.isNotBlank() } ?: "گفتگوی جدید"
}

/** Decoder for the first encrypted archive format shipped by the local hardening branch. */
private object ChatArchiveCodecV1 {
    private const val MAGIC = 0x59434854 // YCHT
    private const val VERSION = 1
    private const val MAX_MESSAGES = 200
    private const val MAX_MESSAGE_BYTES = 1_000_000

    fun decode(payload: ByteArray): List<ChatLine> {
        require(payload.size <= EncryptedChatCrypto.MAX_PLAINTEXT_BYTES) { "Chat archive is too large" }
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == MAGIC) { "Invalid chat archive header" }
            require(input.readInt() == VERSION) { "Unsupported chat archive version" }
            val count = input.readInt()
            require(count in 0..MAX_MESSAGES) { "Invalid chat message count" }
            val lines = ArrayList<ChatLine>(count)
            repeat(count) {
                val roleSize = input.readUnsignedByte()
                require(roleSize in 1..16)
                val role = ByteArray(roleSize).also(input::readFully).toString(Charsets.UTF_8)
                require(role == "user" || role == "assistant")
                val contentSize = input.readInt()
                require(contentSize in 1..MAX_MESSAGE_BYTES)
                val content = ByteArray(contentSize).also(input::readFully).toString(Charsets.UTF_8)
                require(content.isNotBlank())
                lines += ChatLine(role, content)
            }
            require(input.available() == 0) { "Trailing chat archive data" }
            return lines
        }
    }
}

/** Version-one codec retained for compatibility with existing local tests. */
internal object ChatArchiveCodecV1TestCompat {
    fun encode(lines: List<ChatLine>): ByteArray = ChatArchiveCodecTestCompat.encode(lines)
}

private object ChatArchiveCodecTestCompat {
    private const val MAGIC = 0x59434854
    private const val VERSION = 1

    fun encode(lines: List<ChatLine>): ByteArray {
        require(lines.size <= 200)
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeInt(lines.size)
                for (line in lines) {
                    require(line.role == "user" || line.role == "assistant")
                    val role = line.role.toByteArray(Charsets.UTF_8)
                    val content = line.content.toByteArray(Charsets.UTF_8)
                    require(content.isNotBlank() && content.size <= 1_000_000)
                    output.writeByte(role.size)
                    output.write(role)
                    output.writeInt(content.size)
                    output.write(content)
                }
            }
            bytes.toByteArray()
        }
    }
}
