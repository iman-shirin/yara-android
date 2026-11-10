package com.yara.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * On-device Android speech recognition front-end and Android TTS output.
 * Recognition quality and Persian voice availability depend on the device's
 * installed speech services and language packs.
 */
class YaraVoice(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = TextToSpeech(appContext, this)
    private var recognizer: SpeechRecognizer? = null
    private var ttsReady = false

    var onResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onListeningChanged: ((Boolean) -> Unit)? = null
    var onSpeakingChanged: ((Boolean) -> Unit)? = null

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            val result = tts?.setLanguage(Locale.forLanguageTag("fa-IR"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                onError?.invoke("صدای فارسی در موتور گفتار این دستگاه نصب یا پشتیبانی نمی‌شود.")
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { onSpeakingChanged?.invoke(true) }
                override fun onDone(utteranceId: String?) { onSpeakingChanged?.invoke(false) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { onSpeakingChanged?.invoke(false) }
            })
        }
    }

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            onError?.invoke("سرویس تشخیص گفتار روی این دستگاه در دسترس نیست.")
            return
        }
        stopListening()
        recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).also { speech ->
            speech.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { onListeningChanged?.invoke(true) }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { onListeningChanged?.invoke(false) }
                override fun onError(error: Int) {
                    onListeningChanged?.invoke(false)
                    this@YaraVoice.onError?.invoke("تشخیص گفتار انجام نشد (کد $error). دوباره تلاش کن.")
                }
                override fun onResults(results: Bundle?) {
                    onListeningChanged?.invoke(false)
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    if (text.isNotBlank()) onResult?.invoke(text)
                    else this@YaraVoice.onError?.invoke("گفتاری تشخیص داده نشد.")
                }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "با یارا صحبت کن")
            }
            speech.startListening(intent)
        }
    }

    fun speak(text: String) {
        if (!ttsReady || text.isBlank()) {
            onError?.invoke("موتور تبدیل متن به گفتار هنوز آماده نیست.")
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "yara-${System.currentTimeMillis()}")
    }

    fun stop() {
        stopListening()
        tts?.stop()
        onSpeakingChanged?.invoke(false)
    }

    private fun stopListening() {
        recognizer?.let { speech -> runCatching { speech.cancel(); speech.destroy() } }
        recognizer = null
        onListeningChanged?.invoke(false)
    }

    fun release() {
        stop()
        tts?.shutdown()
        tts = null
    }
}
