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

    suspend fun reply(history: List<ChatLine>, message: String, memories: List<String> = emptyList(), webContext: String = ""): String = withContext(Dispatchers.IO) {
        val primary = prefs.primaryProvider
        val approvedMemories = if (prefs.shareMemoriesWithProviders) memories else emptyList()
        require(primary.apiKey.isNotBlank()) { "ابتدا کلید API سرویس ${primary.name} را در تنظیمات وارد کن." }
        try {
            return@withContext request(primary, history, message, approvedMemories, webContext)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val secondary = prefs.fallbackProvider
            if (!prefs.fallbackEnabled || secondary.apiKey.isBlank() || !YaraProviderRetryPolicy.shouldRetry(error)) throw error
            try {
                return@withContext request(secondary, history, message, approvedMemories, webContext)
            } catch (fallbackError: Exception) {
                if (fallbackError is CancellationException) throw fallbackError
                throw IOException("اتصال سرویس اصلی و جایگزین ناموفق بود. ${primary.name}: ${error.message}; ${secondary.name}: ${fallbackError.message}", fallbackError)
            }
        }
    }


    private fun request(provider: ProviderConfig, history: List<ChatLine>, message: String, memories: List<String>, webContext: String): String {
        require(provider.apiKey.isNotBlank()) { "کلید API سرویس ${provider.name} وارد نشده است." }
        val memoryContext = memories.take(8).joinToString("\n")
        val systemPrompt = buildString {
            append("تو یارا هستی؛ دستیار فارسی‌زبان، دقیق، امن و کاربردی.")
            if (memoryContext.isNotBlank()) {
                append("\n\nاطلاعات حافظه بلندمدت کاربر که برای درخواست فعلی مرتبط تشخیص داده شده‌اند؛ فقط در صورت ارتباط استفاده کن و آن‌ها را به‌عنوان حقیقت قطعی درباره موضوعات متغیر تلقی نکن:\n")
                append(memoryContext)
            }
            if (webContext.isNotBlank()) {
                append("\n\nزمینه جستجوی اینترنتی برای همین درخواست:\n")
                append(webContext)
            }
        }
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt))
        history.takeLast(30).forEach { messages.put(JSONObject().put("role", it.role).put("content", it.content)) }
        messages.put(JSONObject().put("role", "user").put("content", message))
        val body = JSONObject().put("model", provider.model).put("messages", messages).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(provider.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${provider.apiKey}")
            .header("Content-Type", "application/json")
            .header("x-goog-api-client", "yara-android/0.2.0")
            .post(body).build()
        return client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}: ${responseText.take(400)}")
            JSONObject(responseText).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        }
    }
}
