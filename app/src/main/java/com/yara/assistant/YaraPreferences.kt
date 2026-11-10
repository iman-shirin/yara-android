package com.yara.assistant

import android.content.Context

class YaraPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("yara_settings", Context.MODE_PRIVATE)

    val baseUrl: String
        get() = preferences.getString("base", "https://api.openai.com/v1")
            ?: "https://api.openai.com/v1"

    val model: String
        get() = preferences.getString("model", "gpt-4o-mini")
            ?: "gpt-4o-mini"

    val apiKey: String
        get() = preferences.getString("key", "").orEmpty()

    fun save(base: String, model: String, key: String) {
        preferences.edit()
            .putString("base", base)
            .putString("model", model)
            .putString("key", key)
            .apply()
    }
}
