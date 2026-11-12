package com.yara.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class YaraProviderRouterTest {
    private val groq = ProviderConfig("groq", "Groq", "https://groq.example/v1", "g-model", "g-key")
    private val gemini = ProviderConfig("gemini", "Gemini", "https://gemini.example/v1", "m-model", "m-key")

    @Test
    fun groqCanBePrimaryWithGeminiFallback() {
        assertEquals(groq, YaraProviderRouter.primary("groq", groq, gemini))
        assertEquals(gemini, YaraProviderRouter.fallback("groq", groq, gemini))
    }

    @Test
    fun geminiCanBePrimaryWithGroqFallback() {
        assertEquals(gemini, YaraProviderRouter.primary("gemini", groq, gemini))
        assertEquals(groq, YaraProviderRouter.fallback("gemini", groq, gemini))
    }

    @Test
    fun unknownProviderIdSafelyDefaultsToGroq() {
        assertEquals(groq, YaraProviderRouter.primary("unknown", groq, gemini))
        assertEquals(gemini, YaraProviderRouter.fallback("unknown", groq, gemini))
    }
}
