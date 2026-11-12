package com.yara.assistant

object YaraProviderRouter {
    fun primary(id: String, groq: ProviderConfig, gemini: ProviderConfig): ProviderConfig =
        if (id == "gemini") gemini else groq

    fun fallback(id: String, groq: ProviderConfig, gemini: ProviderConfig): ProviderConfig =
        if (id == "gemini") groq else gemini
}
