package com.yara.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ChatStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("yara_chat", Context.MODE_PRIVATE)

    fun load(): List<ChatLine> {
        val raw = preferences.getString("lines", "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val role = item.optString("role")
                    val content = item.optString("content")
                    if (role in ALLOWED_ROLES && content.isNotBlank()) {
                        add(ChatLine(role, content))
                    }
                }
            }.takeLast(MAX_MESSAGES)
        }.getOrElse {
            // Preserve app usability if a previous app version left malformed JSON.
            emptyList()
        }
    }

    fun save(lines: List<ChatLine>) {
        val array = JSONArray()
        lines.takeLast(MAX_MESSAGES)
            .filter { it.role in ALLOWED_ROLES && it.content.isNotBlank() }
            .forEach { line ->
                array.put(
                    JSONObject()
                        .put("role", line.role)
                        .put("content", line.content)
                )
            }
        check(preferences.edit().putString("lines", array.toString()).commit()) {
            "ذخیره تاریخچه گفتگو انجام نشد."
        }
    }

    private companion object {
        const val MAX_MESSAGES = 200
        val ALLOWED_ROLES = setOf("user", "assistant")
    }
}
