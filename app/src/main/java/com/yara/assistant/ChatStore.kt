package com.yara.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ChatStore(context: Context) {
    private val p = context.getSharedPreferences("yara_chat", Context.MODE_PRIVATE)
    fun load(): List<ChatLine> {
        val raw = p.getString("lines", "[]") ?: "[]"
        val a = JSONArray(raw)
        return (0 until a.length()).map { i -> a.getJSONObject(i).let { ChatLine(it.getString("role"), it.getString("content")) } }
    }
    fun save(lines: List<ChatLine>) {
        val a = JSONArray()
        lines.takeLast(200).forEach { a.put(JSONObject().put("role", it.role).put("content", it.content)) }
        p.edit().putString("lines", a.toString()).apply()
    }
}
