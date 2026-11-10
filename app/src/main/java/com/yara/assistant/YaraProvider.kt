package com.yara.assistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class YaraProvider(private val prefs: YaraPreferences) {
    private val client = OkHttpClient.Builder()
        .callTimeout(90, TimeUnit.SECONDS).connectTimeout(20, TimeUnit.SECONDS).build()

    suspend fun reply(history: List<ChatLine>, message: String): String = withContext(Dispatchers.IO) {
        val primary = prefs.groq
        require(primary.apiKey.isNotBlank()) { "ابتدا کلید API سرویس Groq را در تنظیمات وارد کن." }
        try {
            request(primary, history, message)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val secondary = prefs.gemini
            if (!prefs.fallbackEnabled || secondary.apiKey.isBlank() || !isRetryable(error)) throw error
            try {
                request(secondary, history, message)
            } catch (fallbackError: Exception) {
                if (fallbackError is CancellationException) throw fallbackError
                throw IOException("اتصال Groq و Gemini ناموفق بود. Groq: ${error.message}; Gemini: ${fallbackError.message}", fallbackError)
            }
        }
    }

    private fun isRetryable(error: Exception): Boolean =
        error is IOException || error.message?.contains("HTTP 408") == true ||
            error.message?.contains("HTTP 429") == true || error.message?.contains("HTTP 5") == true

    private fun request(provider: ProviderConfig, history: List<ChatLine>, message: String): String {
        require(provider.apiKey.isNotBlank()) { "کلید API سرویس ${provider.name} وارد نشده است." }
        val messages = JSONArray().put(JSONObject().put("role", "system")
            .put("content", "تو یارا هستی؛ دستیار فارسی‌زبان، دقیق، امن و کاربردی."))
        history.takeLast(30).forEach { messages.put(JSONObject().put("role", it.role).put("content", it.content)) }
        messages.put(JSONObject().put("role", "user").put("content", message))
        val body = JSONObject().put("model", provider.model).put("messages", messages).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(provider.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${provider.apiKey}")
            .header("Content-Type", "application/json")
            .header("x-goog-api-client", "yara-android/0.2.0")
            .post(body).build()
        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}: ${responseText.take(400)}")
            JSONObject(responseText).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        }
    }
}
