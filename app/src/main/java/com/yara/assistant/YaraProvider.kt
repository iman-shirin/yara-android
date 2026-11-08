package com.yara.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class YaraProvider(private val prefs: YaraPreferences) {
    private val client = OkHttpClient.Builder()
        .callTimeout(90, TimeUnit.SECONDS).connectTimeout(20, TimeUnit.SECONDS).build()

    suspend fun reply(history: List<ChatLine>, message: String): String = withContext(Dispatchers.IO) {
        require(prefs.apiKey.isNotBlank()) { "ابتدا کلید API را در تنظیمات وارد کن." }
        require(prefs.model.isNotBlank()) { "نام مدل خالی است." }
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "تو یارا هستی؛ دستیار فارسی‌زبان، دقیق، امن و کاربردی."))
        history.takeLast(30).forEach { messages.put(JSONObject().put("role", it.role).put("content", it.content)) }
        messages.put(JSONObject().put("role", "user").put("content", message))
        val body = JSONObject().put("model", prefs.model).put("messages", messages).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val url = prefs.baseUrl.trimEnd('/') + "/chat/completions"
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${prefs.apiKey}")
            .post(body).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}: ${text.take(500)}")
            val json = JSONObject(text)
            json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        }
    }
}
