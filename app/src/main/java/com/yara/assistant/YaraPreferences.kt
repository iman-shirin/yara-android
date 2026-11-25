package com.yara.assistant

import android.content.Context

data class ProviderConfig(val id: String, val name: String, val baseUrl: String, val model: String, val apiKey: String)

class YaraPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("yara_settings", Context.MODE_PRIVATE)
    private val secureSecrets = SecureSecretStore(context.applicationContext)

    private fun legacyKey(): String {
        val current = secureSecrets.read("groq_api_key")
        if (current.isNotBlank()) return current
        val oldEncrypted = secureSecrets.read("api_key")
        val oldPlain = preferences.getString("key", "").orEmpty()
        val old = oldEncrypted.ifBlank { oldPlain }
        if (old.isNotBlank()) {
            secureSecrets.write("groq_api_key", old)
            secureSecrets.write("api_key", "")
            preferences.edit().remove("key").apply()
        }
        return old
    }

    val groq: ProviderConfig get() = ProviderConfig(
        "groq", "Groq",
        preferences.getString("base", "https://api.groq.com/openai/v1") ?: "https://api.groq.com/openai/v1",
        preferences.getString("model", "openai/gpt-oss-120b") ?: "openai/gpt-oss-120b",
        legacyKey()
    )

    val gemini: ProviderConfig get() = ProviderConfig(
        "gemini", "Google Gemini",
        preferences.getString("gemini_base", "https://generativelanguage.googleapis.com/v1beta/openai/") ?: "https://generativelanguage.googleapis.com/v1beta/openai/",
        preferences.getString("gemini_model", "gemini-3.8-flash") ?: "gemini-3.8-flash",
        secureSecrets.read("gemini_api_key")
    )

    val fallbackEnabled: Boolean get() = preferences.getBoolean("fallback_enabled", false)
    val shareMemoriesWithProviders: Boolean get() = preferences.getBoolean("share_memories_with_providers", false)
    val primaryProviderId: String get() = preferences.getString("primary_provider", "groq")?.takeIf { it == "groq" || it == "gemini" } ?: "groq"
    val primaryProvider: ProviderConfig get() = YaraProviderRouter.primary(primaryProviderId, groq, gemini)
    val fallbackProvider: ProviderConfig get() = YaraProviderRouter.fallback(primaryProviderId, groq, gemini)
    val baseUrl: String get() = groq.baseUrl
    val model: String get() = groq.model
    val apiKey: String get() = groq.apiKey

    fun saveProviders(groqBase: String, groqModel: String, groqKey: String,
                      geminiBase: String, geminiModel: String, geminiKey: String, fallback: Boolean,
                      primaryProviderId: String,
                      shareMemories: Boolean = shareMemoriesWithProviders) {
        require(groqBase.startsWith("https://", true)) { "نشانی Groq باید با HTTPS شروع شود." }
        require(groqModel.isNotBlank()) { "نام مدل Groq خالی است." }
        require(geminiBase.startsWith("https://", true)) { "نشانی Gemini باید با HTTPS شروع شود." }
        require(geminiModel.isNotBlank()) { "نام مدل Gemini خالی است." }
        require(primaryProviderId == "groq" || primaryProviderId == "gemini") { "سرویس اصلی معتبر نیست." }
        secureSecrets.write("groq_api_key", groqKey.trim())
        secureSecrets.write("gemini_api_key", geminiKey.trim())
        preferences.edit().putString("base", groqBase.trim()).putString("model", groqModel.trim())
            .putString("gemini_base", geminiBase.trim()).putString("gemini_model", geminiModel.trim())
            .putBoolean("fallback_enabled", fallback).putBoolean("share_memories_with_providers", shareMemories)
            .putString("primary_provider", primaryProviderId).remove("key").apply()
    }

    fun save(base: String, model: String, key: String) =
        saveProviders(base, model, key, gemini.baseUrl, gemini.model, gemini.apiKey, fallbackEnabled, primaryProviderId)
}
