package com.yara.assistant

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebViewClient
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

data class ChatLine(val role: String, val content: String)

class MainActivity : ComponentActivity() {
    private val prefs by lazy { YaraPreferences(this) }
    private val store by lazy { ChatStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val saved = runCatching { store.load() }.getOrDefault(emptyList())
            setContent {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        primary = Color(0xFFB7A0FF),
                        secondary = Color(0xFF70D9D0),
                        background = Color(0xFF0C1020),
                        surface = Color(0xFF171D32)
                    )
                ) {
                    var lines by remember { mutableStateOf(saved) }
                    var input by remember { mutableStateOf("") }
                    var busy by remember { mutableStateOf(false) }
                    val context = LocalContext.current
                    val voice = remember { YaraVoice(context) }
                    var listening by remember { mutableStateOf(false) }
                    var speaking by remember { mutableStateOf(false) }
                    var voiceError by remember { mutableStateOf<String?>(null) }
                    var voiceStatus by remember { mutableStateOf("") }
                    var voiceOutput by remember { mutableStateOf(true) }
                    val microphonePermission = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { granted ->
                        if (granted) voice.startListening()
                        else voiceError = "برای ورود صوتی، اجازه میکروفون لازم است."
                    }
                    DisposableEffect(voice) {
                        voice.onResult = { recognized -> input = recognized; voiceError = null }
                        voice.onError = { message -> voiceError = message }
                        voice.onStatus = { message -> voiceStatus = message }
                        voice.onListeningChanged = { listening = it }
                        voice.onSpeakingChanged = { speaking = it }
                        onDispose { voice.release() }
                    }
                    var showSettings by remember { mutableStateOf(false) }
                    var showConversation by remember { mutableStateOf(saved.isNotEmpty()) }
                    val listState = rememberLazyListState()

                    Scaffold(
                        containerColor = Color(0xFF0C1020),
                        topBar = {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        modifier = Modifier.size(42.dp),
                                        shape = RoundedCornerShape(14.dp),
                                        color = Color(0xFF282448),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFB7A0FF).copy(alpha = 0.35f))
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text("Y", color = Color(0xFFB7A0FF), fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
                                        }
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text("یارا", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                                        Text("دستیار هوش مصنوعی", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9BA8C7))
                                    }
                                }
                                Surface(
                                    onClick = { showSettings = true },
                                    shape = RoundedCornerShape(13.dp),
                                    color = Color(0xFF1A2440),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.07f))
                                ) {
                                    Text("⚙  تنظیمات", Modifier.padding(horizontal = 13.dp, vertical = 10.dp), color = Color(0xFFDAE2F5), style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    ) { pad ->
                        Column(
                            Modifier.fillMaxSize().padding(pad).background(Brush.verticalGradient(listOf(Color(0xFF111A31), Color(0xFF080D1B), Color(0xFF090E1C)))).padding(horizontal = 18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Spacer(Modifier.height(8.dp))
                            Box(
                                Modifier.fillMaxWidth().weight(if (showConversation) 0.85f else 1.25f),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    YaraAvatar(isSpeaking = speaking, hasConversation = showConversation, modifier = Modifier.size(238.dp))
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        when { busy -> "در حال فکر کردن…" ; showConversation -> "من اینجا هستم" ; else -> "سلام، من یارا هستم" },
                                        color = Color.White,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        "دستیار هوشمند شخصی",
                                        color = Color(0xFF9BA8C7),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Spacer(Modifier.height(11.dp))
                                    StatusPill(active = !busy, label = if (busy) "در حال پردازش" else "آماده گفتگو")
                                    if (!showConversation) {
                                        Spacer(Modifier.height(22.dp))
                                        QuickPrompts(onSelect = { input = it }, modifier = Modifier.fillMaxWidth())
                                    }
                                }
                            }

                            if (showConversation && lines.isNotEmpty()) {
                                LazyColumn(
                                    Modifier.fillMaxWidth().weight(1f),
                                    state = listState,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    contentPadding = PaddingValues(vertical = 8.dp)
                                ) {
                                    items(lines.takeLast(30)) { line ->
                                        val user = line.role == "user"
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
                                            Surface(
                                                color = if (user) Color(0xFF34304F) else Color(0xFF1A2940),
                                                shape = RoundedCornerShape(18.dp),
                                                modifier = Modifier.widthIn(max = 310.dp)
                                            ) {
                                                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                                    Text(if (user) "شما" else "یارا", color = Color(0xFFB7A0FF), style = MaterialTheme.typography.labelSmall)
                                                    Spacer(Modifier.height(3.dp))
                                                    Text(line.content, color = Color(0xFFF2F4FC), style = MaterialTheme.typography.bodyMedium)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Row(
                                Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = input,
                                    onValueChange = { input = it },
                                    modifier = Modifier.weight(1f),
                                    placeholder = { Text("با یارا صحبت کن…") },
                                    enabled = !busy,
                                    shape = RoundedCornerShape(22.dp),
                                    maxLines = 4
                                )
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    FilledTonalButton(
                                        enabled = !busy && !listening,
                                        onClick = {
                                            voiceError = null
                                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                                voice.startListening()
                                            } else {
                                                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                                            }
                                        },
                                        shape = CircleShape,
                                        contentPadding = PaddingValues(12.dp)
                                    ) { Text(if (listening) "…" else "🎙") }
                                    Text(if (listening) "گوش می‌دهم" else "صوت", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9BA8C7))
                                }
                                Button(
                                    enabled = input.isNotBlank() && !busy,
                                    onClick = {
                                        val msg = input.trim()
                                        input = ""
                                        val previous = lines
                                        lines = previous + ChatLine("user", msg)
                                        showConversation = true
                                        busy = true
                                        lifecycleScope.launch {
                                            runCatching { store.save(lines) }
                                            val memoryStore = YaraMemoryStore(context)
                                            val reply = runCatching {
                                                when (val command = YaraMemoryCommandParser.parse(msg)) {
                                                    is YaraMemoryCommand.Save -> {
                                                        memoryStore.save(command.content)
                                                        "این مورد در حافظه بلندمدت یارا ذخیره شد."
                                                    }
                                                    is YaraMemoryCommand.Forget -> {
                                                        val removed = memoryStore.deleteMatching(command.content)
                                                        if (removed > 0) "$removed مورد از حافظه حذف شد."
                                                        else "مورد مطابقی در حافظه پیدا نشد."
                                                    }
                                                    YaraMemoryCommand.List -> {
                                                        val saved = memoryStore.list(limit = 50)
                                                        if (saved.isEmpty()) "هنوز موردی در حافظه بلندمدت ثبت نشده است."
                                                        else saved.joinToString("\\n") { "${it.id}. [${it.project}] ${it.content}" }
                                                    }
                                                    null -> YaraProvider(prefs).reply(previous, msg, memoryStore.relevant(msg))
                                                }

                                            }.getOrElse { "انجام درخواست ناموفق بود: ${it.message}" }
                                            lines = lines + ChatLine("assistant", reply)
                                            runCatching { store.save(lines) }
                                            if (voiceOutput && !reply.startsWith("اتصال به مدل برقرار نشد:")) voice.speak(reply)
                                            busy = false
                                        }
                                    },
                                    shape = CircleShape,
                                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)
                                ) { Text(if (busy) "…" else "ارسال") }
                            }
                            if (voiceStatus.isNotBlank()) {
                                Text(voiceStatus, color = Color(0xFF70D9D0), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp))
                            }
                            if (voiceError != null) {
                                Text(voiceError!!, color = Color(0xFFFF9C9C), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("پاسخ صوتی", color = Color(0xFF9BA8C7), style = MaterialTheme.typography.labelMedium)
                                Switch(checked = voiceOutput, onCheckedChange = { voiceOutput = it })
                            }
                        }
                    }
                    if (showSettings) SettingsDialog(prefs) { showSettings = false }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(active: Boolean, label: String) {
    Surface(
        color = Color(0xFF17263A),
        shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF70D9D0).copy(alpha = 0.18f))
    ) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(if (active) Color(0xFF70D9D0) else Color(0xFFB7A0FF), CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(label, color = Color(0xFFD4E5E8), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun QuickPrompts(onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text("از کجا شروع کنیم؟", color = Color(0xFFDDE3F4), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("امروز چه کارهایی دارم؟", "یک موضوع را بررسی کن", "برایم برنامه‌ریزی کن").forEach { prompt ->
                AssistChip(
                    onClick = { onSelect(prompt) },
                    label = { Text(prompt, maxLines = 1) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = Color(0xFF17223A), labelColor = Color(0xFFD9DDF0)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                )
            }
        }
    }
}

@Composable
private fun YaraAvatar(isSpeaking: Boolean, hasConversation: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            webViewRef.value?.stopLoading()
            webViewRef.value?.destroy()
            webViewRef.value = null
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val assetLoader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
                .build()
            WebView(ctx).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = true
                settings.mediaPlaybackRequiresUserGesture = false
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                        assetLoader.shouldInterceptRequest(request.url)
                }
                webViewRef.value = this
                loadUrl("https://appassets.androidplatform.net/assets/yara_avatar.html")
            }
        },
        update = { view ->
            view.evaluateJavascript(
                "window.yaraSetState && window.yaraSetState($isSpeaking, $hasConversation)",
                null
            )
        }
    )
}

@Composable
private fun SettingsDialog(prefs: YaraPreferences, close: () -> Unit) {
    val initialGroq = prefs.groq
    val initialGemini = prefs.gemini
    var groqBase by remember { mutableStateOf(initialGroq.baseUrl) }
    var groqModel by remember { mutableStateOf(initialGroq.model) }
    var groqKey by remember { mutableStateOf(initialGroq.apiKey) }
    var geminiBase by remember { mutableStateOf(initialGemini.baseUrl) }
    var geminiModel by remember { mutableStateOf(initialGemini.model) }
    var geminiKey by remember { mutableStateOf(initialGemini.apiKey) }
    var fallback by remember { mutableStateOf(prefs.fallbackEnabled) }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = close,
        title = { Text("اتصال سرویس‌های هوش مصنوعی") },
        text = {
            Column(
                Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("سرویس اصلی: Groq", color = Color(0xFF70D9D0), fontWeight = FontWeight.Bold)
                OutlinedTextField(groqBase, { groqBase = it }, label = { Text("نشانی پایه Groq") }, singleLine = true)
                OutlinedTextField(groqModel, { groqModel = it }, label = { Text("مدل Groq") }, singleLine = true)
                OutlinedTextField(groqKey, { groqKey = it }, label = { Text("کلید API Groq") }, singleLine = true, visualTransformation = PasswordVisualTransformation())

                Divider()
                Text("سرویس جایگزین: Google Gemini", color = Color(0xFF70D9D0), fontWeight = FontWeight.Bold)
                OutlinedTextField(geminiBase, { geminiBase = it }, label = { Text("نشانی پایه Gemini") }, singleLine = true)
                OutlinedTextField(geminiModel, { geminiModel = it }, label = { Text("مدل Gemini") }, singleLine = true)
                OutlinedTextField(geminiKey, { geminiKey = it }, label = { Text("کلید API Gemini") }, singleLine = true, visualTransformation = PasswordVisualTransformation())

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = fallback, onCheckedChange = { fallback = it })
                    Text("استفاده خودکار از Gemini هنگام اختلال Groq")
                }
                Text("کلیدها به‌صورت رمزنگاری‌شده در Android Keystore نگهداری می‌شوند. کلیدها را در چت یا مخزن کد منتشر نکن.", style = MaterialTheme.typography.bodySmall)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching {
                    prefs.saveProviders(groqBase, groqModel, groqKey, geminiBase, geminiModel, geminiKey, fallback)
                }.onSuccess { close() }
                    .onFailure { error = it.message ?: "ذخیره تنظیمات ناموفق بود." }
            }) { Text("ذخیره") }
        },
        dismissButton = { TextButton(onClick = close) { Text("انصراف") } }
    )
}
