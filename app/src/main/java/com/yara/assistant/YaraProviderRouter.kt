package com.yara.assistant

object YaraProviderRouter {
    fun primary(id: String, groq: ProviderConfig, gemini: ProviderConfig): ProviderConfig =
        if (id == "gemini") gemini else groq

    fun fallback(id: String, groq: ProviderConfig, gemini: ProviderConfig): ProviderConfig =
        if (id == "gemini") groq else gemini
}

internal object YaraProviderRetryPolicy {
    fun shouldRetry(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        if (listOf("billing", "payment", "quota", "insufficient funds").any { message.contains(it, ignoreCase = true) }) {
            return false
        }
        val status = Regex("""HTTP\s+(\d{3})""", RegexOption.IGNORE_CASE)
            .find(message)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        if (status != null) {
            // Authentication, billing, quota and rate-limit HTTP responses fail closed.
            return status == 408 || status in 500..599
        }
        return error is java.io.IOException
    }
}
