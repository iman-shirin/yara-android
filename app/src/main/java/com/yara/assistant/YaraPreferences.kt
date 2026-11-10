package com.yara.assistant

import android.content.Context

class YaraPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("yara_settings", Context.MODE_PRIVATE)
    private val secureSecrets = SecureSecretStore(context.applicationContext)

    val baseUrl: String
        get() = preferences.getString("base", "https://api.openai.com/v1")
            ?: "https://api.openai.com/v1"

    val model: String
        get() = preferences.getString("model", "gpt-4o-mini")
            ?: "gpt-4o-mini"

    /**
     * Migrates the legacy plaintext key to Android Keystore encryption on first access.
     * If decryption fails, returns an empty value rather than exposing corrupt data.
     */
    val apiKey: String
        get() {
            val encrypted = secureSecrets.read("api_key")
            if (encrypted.isNotBlank()) return encrypted
            val legacy = preferences.getString("key", "").orEmpty()
            if (legacy.isNotBlank()) {
                runCatching { secureSecrets.write("api_key", legacy) }
                    .onSuccess { preferences.edit().remove("key").apply() }
                return if (preferences.contains("key")) "" else secureSecrets.read("api_key")
            }
            return ""
        }

    fun save(base: String, model: String, key: String) {
        require(base.startsWith("https://", ignoreCase = true)) {
            "نشانی سرویس باید با HTTPS شروع شود."
        }
        require(model.isNotBlank()) { "نام مدل خالی است." }
        secureSecrets.write("api_key", key.trim())
        preferences.edit()
            .putString("base", base.trim())
            .putString("model", model.trim())
            .remove("key")
            .apply()
    }
}
