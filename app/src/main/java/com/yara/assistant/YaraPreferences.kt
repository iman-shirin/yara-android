package com.yara.assistant

import android.content.Context

class YaraPreferences(context: Context) {
    private val p = context.getSharedPreferences("yara_settings", Context.MODE_PRIVATE)
    var baseUrl: String get() = p.getString("base", "https://api.openai.com/v1") ?: "" ; private set
    var model: String get() = p.getString("model", "gpt-4o-mini") ?: "" ; private set
    var apiKey: String get() = p.getString("key", "") ?: "" ; private set
    fun save(base: String, model: String, key: String) {
        p.edit().putString("base", base).putString("model", model).putString("key", key).apply()
    }
}
