package com.yara.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ChatLine(val role: String, val content: String)

data class ChatConversation(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messages: List<ChatLine>
)

/**
 * Durable, local conversation storage.
 *
 * The first read transparently migrates the previous single-chat "lines"
 * preference into the new multi-conversation format. The legacy value is
 * intentionally retained until the new archive has been committed.
 */
class ChatStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("yara_chat", Context.MODE_PRIVATE)

    fun newConversation(): ChatConversation = ChatConversation(
        id = UUID.randomUUID().toString(),
        title = NEW_CHAT_TITLE,
        updatedAt = System.currentTimeMillis(),
        messages = emptyList()
    )

    fun loadActiveConversationId(): String? =
        preferences.getString(KEY_ACTIVE_CONVERSATION, null)

    fun saveActiveConversationId(id: String) {
        check(preferences.edit().putString(KEY_ACTIVE_CONVERSATION, id).commit()) {
            "ذخیره گفتگوی فعال انجام نشد."
        }
    }

    fun loadConversations(): List<ChatConversation> {
        val raw = preferences.getString(KEY_CONVERSATIONS, null)
        if (raw == null) return migrateLegacyConversation()

        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val messages = decodeMessages(item.optJSONArray("messages") ?: JSONArray())
                    if (messages.isNotEmpty()) {
                        val title = item.optString("title").takeIf { it.isNotBlank() }
                            ?: titleFor(messages)
                        add(
                            ChatConversation(
                                id = id,
                                title = title,
                                updatedAt = item.optLong("updatedAt", 0L),
                                messages = messages
                            )
                        )
                    }
                }
            }.sortedByDescending { it.updatedAt }
        }.getOrElse {
            // Do not overwrite a malformed archive automatically: that could
            // destroy recoverable user data. A later explicit save can repair it.
            emptyList()
        }
    }

    fun saveConversation(conversation: ChatConversation) {
        require(conversation.id.isNotBlank()) { "شناسه گفتگو معتبر نیست." }
        val existing = loadConversations()
        val messages = conversation.messages
            .filter { it.role in ALLOWED_ROLES && it.content.isNotBlank() }
            .takeLast(MAX_MESSAGES)
        val oldTitle = existing.firstOrNull { it.id == conversation.id }?.title
        val title = messages.firstOrNull { it.role == "user" }?.content
            ?.replace(Regex("\\s+"), " ")
            ?.take(MAX_TITLE_LENGTH)
            ?.takeIf { it.isNotBlank() }
            ?: oldTitle
            ?: NEW_CHAT_TITLE
        val saved = ChatConversation(
            id = conversation.id,
            title = title,
            updatedAt = System.currentTimeMillis(),
            messages = messages
        )
        val updated = (existing.filterNot { it.id == saved.id } + saved)
            .sortedByDescending { it.updatedAt }
            .take(MAX_CONVERSATIONS)
        persist(updated)
    }

    private fun migrateLegacyConversation(): List<ChatConversation> {
        val legacyRaw = preferences.getString(KEY_LEGACY_LINES, null) ?: return emptyList()
        val legacyMessages = runCatching {
            decodeMessages(JSONArray(legacyRaw))
        }.getOrDefault(emptyList())
        if (legacyMessages.isEmpty()) {
            // Commit an empty archive marker so migration is not repeated forever.
            persist(emptyList())
            return emptyList()
        }

        val migrated = ChatConversation(
            id = UUID.randomUUID().toString(),
            title = titleFor(legacyMessages),
            updatedAt = System.currentTimeMillis(),
            messages = legacyMessages
        )
        persist(listOf(migrated))
        if (loadActiveConversationId() == null) saveActiveConversationId(migrated.id)
        return listOf(migrated)
    }

    private fun decodeMessages(array: JSONArray): List<ChatLine> = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val role = item.optString("role")
            val content = item.optString("content")
            if (role in ALLOWED_ROLES && content.isNotBlank()) {
                add(ChatLine(role, content))
            }
        }
    }.takeLast(MAX_MESSAGES)

    private fun persist(conversations: List<ChatConversation>) {
        val array = JSONArray()
        conversations.forEach { conversation ->
            val messages = JSONArray()
            conversation.messages
                .filter { it.role in ALLOWED_ROLES && it.content.isNotBlank() }
                .takeLast(MAX_MESSAGES)
                .forEach { line ->
                    messages.put(
                        JSONObject()
                            .put("role", line.role)
                            .put("content", line.content)
                    )
                }
            array.put(
                JSONObject()
                    .put("id", conversation.id)
                    .put("title", conversation.title)
                    .put("updatedAt", conversation.updatedAt)
                    .put("messages", messages)
            )
        }
        check(preferences.edit().putString(KEY_CONVERSATIONS, array.toString()).commit()) {
            "ذخیره سوابق گفتگو انجام نشد."
        }
    }

    private fun titleFor(messages: List<ChatLine>): String =
        messages.firstOrNull { it.role == "user" }?.content
            ?.replace(Regex("\\s+"), " ")
            ?.take(MAX_TITLE_LENGTH)
            ?.takeIf { it.isNotBlank() }
            ?: NEW_CHAT_TITLE

    private companion object {
        const val KEY_CONVERSATIONS = "conversations"
        const val KEY_ACTIVE_CONVERSATION = "active_conversation_id"
        const val KEY_LEGACY_LINES = "lines"
        const val NEW_CHAT_TITLE = "گفتگوی جدید"
        const val MAX_MESSAGES = 500
        const val MAX_CONVERSATIONS = 200
        const val MAX_TITLE_LENGTH = 48
        val ALLOWED_ROLES = setOf("user", "assistant")
    }
}
