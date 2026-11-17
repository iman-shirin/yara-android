package com.yara.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Persian ASR plus a dedicated offline Piper/Sherpa Persian voice.
 * Android's default TTS is deliberately not used: it can silently fall back
 * to an unrelated system language when Persian voice data is absent.
 */
class YaraVoice(context: Context) {
    private val appContext = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var tts: OfflineTts? = null
    @Volatile private var activeTrack: AudioTrack? = null
    @Volatile private var released = false

    var onResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onStatus: ((String) -> Unit)? = null
    var onListeningChanged: ((Boolean) -> Unit)? = null
    var onSpeakingChanged: ((Boolean) -> Unit)? = null
    var onMouthLevel: ((Float) -> Unit)? = null

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
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
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
        if (released || text.isBlank()) return
        worker.execute {
            try {
                val engine = ensurePersianEngine()
                if (released) return@execute
                onStatus?.invoke("در حال تولید صدای فارسی…")
                val audio = engine.generate(text)
                if (audio.samples.isEmpty()) throw IllegalStateException("مدل صدای فارسی خروجی تولید نکرد.")
                playPcm(audio.samples, audio.sampleRate)
            } catch (e: Exception) {
                onSpeakingChanged?.invoke(false)
                onError?.invoke("صدای فارسی آماده نشد: ${e.message ?: "خطای نامشخص"}")
            } finally {
                onStatus?.invoke("")
            }
        }
    }

    @Synchronized
    private fun ensurePersianEngine(): OfflineTts {
        tts?.let { return it }
        val root = File(appContext.filesDir, "yara-tts")
        val modelDir = File(root, "vits-piper-fa_IR-amir-medium")
        val model = File(modelDir, "fa_IR-amir-medium.onnx")
        val tokens = File(modelDir, "tokens.txt")
        val espeak = File(modelDir, "espeak-ng-data")
        if (!model.isFile || !tokens.isFile || !espeak.isDirectory) {
            onStatus?.invoke("دریافت موتور صدای فارسی (فقط بار اول)…")
            downloadAndExtract(root)
        }
        if (!model.isFile || !tokens.isFile || !espeak.isDirectory) {
            throw IllegalStateException("فایل‌های مدل فارسی ناقص هستند.")
        }
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = model.absolutePath,
                    tokens = tokens.absolutePath,
                    dataDir = espeak.absolutePath
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu"
            )
        )
        return OfflineTts(null, config).also { tts = it }
    }

    private fun downloadAndExtract(root: File) {
        if (!root.exists() && !root.mkdirs()) throw IllegalStateException("ساخت پوشه مدل ممکن نشد.")
        val archive = File(root, "persian-voice.tar.bz2.part")
        val request = Request.Builder()
            .url("https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-fa_IR-amir-medium.tar.bz2")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("دریافت مدل با خطای HTTP ${response.code} روبه‌رو شد.")
            val body = response.body ?: throw IllegalStateException("پاسخ دانلود خالی است.")
            body.byteStream().use { input ->
                FileOutputStream(archive).use { output -> input.copyTo(output) }
            }
        }
        val temp = File(root, "extracting")
        temp.deleteRecursively()
        if (!temp.mkdirs()) throw IllegalStateException("ساخت پوشه استخراج ممکن نشد.")
        BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive))).use { bz ->
            TarArchiveInputStream(bz).use { tar ->
                var entry = tar.nextTarEntry
                while (entry != null) {
                    val output = File(temp, entry.name).canonicalFile
                    if (!output.path.startsWith(temp.canonicalPath + File.separator)) {
                        throw SecurityException("مسیر نامعتبر در آرشیو مدل")
                    }
                    if (entry.isDirectory) {
                        output.mkdirs()
                    } else {
                        output.parentFile?.mkdirs()
                        FileOutputStream(output).use { stream -> tar.copyTo(stream) }
                    }
                    entry = tar.nextTarEntry
                }
            }
        }
        val extractedModelDir = File(temp, "vits-piper-fa_IR-amir-medium")
        if (!File(extractedModelDir, "fa_IR-amir-medium.onnx").isFile) {
            throw IllegalStateException("ساختار آرشیو مدل فارسی معتبر نیست.")
        }
        val finalDir = File(root, "vits-piper-fa_IR-amir-medium")
        finalDir.deleteRecursively()
        if (!extractedModelDir.renameTo(finalDir)) {
            extractedModelDir.copyRecursively(finalDir, overwrite = true)
        }
        temp.deleteRecursively()
        archive.delete()
    }

    private fun playPcm(samples: FloatArray, sampleRate: Int) {
        stopPlayback()
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(4096)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        activeTrack = track
        onSpeakingChanged?.invoke(true)
        try {
            track.play()
            var offset = 0
            while (offset < samples.size && !released && activeTrack === track) {
                val requested = minOf(2048, samples.size - offset)
                val written = track.write(samples, offset, requested, AudioTrack.WRITE_BLOCKING)
                if (written < 0) throw IllegalStateException("پخش صدای فارسی با خطای AudioTrack روبه‌رو شد: $written")
                if (written == 0) {
                    Thread.yield()
                } else {
                    var energy = 0.0
                    for (index in offset until offset + written) {
                        val sample = samples[index].toDouble()
                        energy += sample * sample
                    }
                    val rms = kotlin.math.sqrt(energy / written).toFloat()
                    runCatching { onMouthLevel?.invoke((rms * 3.2f).coerceIn(0f, 1f)) }
                    offset += written
                }
            }
        } finally {
            runCatching { onMouthLevel?.invoke(0f) }
            if (activeTrack === track) activeTrack = null
            runCatching { track.stop() }
            runCatching { track.flush() }
            runCatching { track.release() }
            runCatching { onSpeakingChanged?.invoke(false) }
        }
    }

    private fun stopPlayback() {
        val track = activeTrack ?: return
        activeTrack = null
        runCatching { track.pause(); track.flush(); track.stop(); track.release() }
        onSpeakingChanged?.invoke(false)
    }

    fun stop() {
        stopListening()
        stopPlayback()
    }

    private fun stopListening() {
        recognizer?.let { speech -> runCatching { speech.cancel(); speech.destroy() } }
        recognizer = null
        onListeningChanged?.invoke(false)
    }

    fun release() {
        released = true
        stop()
        worker.shutdownNow()
        synchronized(this) {
            runCatching { tts?.release() }
            tts = null
        }
    }
}
