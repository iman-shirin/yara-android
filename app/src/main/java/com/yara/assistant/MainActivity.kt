package com.yara.assistant

import android.os.Bundle
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
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
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
                    val context = LocalContext.current
                    val voice = remember { YaraVoice(context) }
                    var lines by remember { mutableStateOf(saved) }
                    var input by remember { mutableStateOf("") }
                    var busy by remember { mutableStateOf(false) }
                    var listening by remember { mutableStateOf(false) }
                    var speaking by remember { mutableStateOf(false) }
                    var voiceError by remember { mutableStateOf<String?>(null) }
                    var voiceOutput by remember { mutableStateOf(true) }
                    var showSettings by remember { mutableStateOf(false) }
                    var showConversation by remember { mutableStateOf(saved.isNotEmpty()) }
                    val listState = rememberLazyListState()
                    val microphonePermission = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { granted ->
                        if (granted) voice.startListening()
                        else voiceError = "برای ورود صوتی، اجازه میکروفون لازم است."
                    }
                    DisposableEffect(voice) {
                        voice.onResult = { recognized -> input = recognized; voiceError = null }
                        voice.onError = { message -> voiceError = message }
                        voice.onListeningChanged = { listening = it }
                        voice.onSpeakingChanged = { speaking = it }
                        onDispose { voice.release() }
                    }

                    Scaffold(
                        containerColor = Color(0xFF0C1020),
                        topBar = {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("یارا", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
                                    Text("دستیار هوشمند شخصی", style = MaterialTheme.typography.labelMedium, color = Color(0xFF9BA8C7))
                                }
                                TextButton(onClick = { showSettings = true }) { Text("تنظیمات") }
                            }
                        }
                    ) { pad ->
                        Column(
                            Modifier.fillMaxSize().padding(pad).padding(horizontal = 18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Spacer(Modifier.height(8.dp))
                            Box(
                                Modifier.fillMaxWidth().weight(if (showConversation) 0.85f else 1.25f),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    YaraAvatar(isSpeaking = speaking, hasConversation = showConversation, modifier = Modifier.size(250.dp))
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        when { busy -> "در حال فکر کردن…" ; showConversation -> "من اینجا هستم" ; else -> "سلام، من یارا هستم" },
                                        color = Color.White,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        "همراه هوشمند تو",
                                        color = Color(0xFF9BA8C7),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    if (!showConversation) {
                                        Spacer(Modifier.height(8.dp))
                                        Text("برای شروع، پیام بده", color = Color(0xFF9BA8C7), style = MaterialTheme.typography.bodySmall)
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
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                                            val reply = runCatching { YaraProvider(prefs).reply(previous, msg) }
                                                .getOrElse { "اتصال به مدل برقرار نشد: ${it.message}" }
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
private fun YaraAvatar(isSpeaking: Boolean, hasConversation: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "yara-face")
    val blink by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.08f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 3600
                1f at 0
                1f at 3000
                0.08f at 3070 with FastOutSlowInEasing
                1f at 3150
                1f at 3600
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "blink"
    )
    val mouthMotion by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes { durationMillis = 520; 0f at 0; 1f at 260; 0f at 520 },
            repeatMode = RepeatMode.Restart
        ),
        label = "mouth"
    )
    val mouthOpen = if (isSpeaking) mouthMotion else 0.12f

    Canvas(modifier = modifier) {
        val c = center
        val unit = size.minDimension
        val faceSize = unit * 0.72f
        val faceTop = c.y - faceSize * 0.48f
        val faceLeft = c.x - faceSize / 2f
        drawCircle(
            brush = Brush.radialGradient(listOf(Color(0xFF354C78), Color(0xFF161D35), Color(0x00101528)), center = c, radius = unit * 0.49f),
            radius = unit * 0.49f,
            center = c
        )
        drawCircle(Color(0xFF8F7BFF).copy(alpha = 0.12f), radius = faceSize * 0.56f, center = c, style = Stroke(width = unit * 0.012f))
        drawOval(
            brush = Brush.linearGradient(listOf(Color(0xFFDBE9FF), Color(0xFF91A9D8), Color(0xFF52658F))),
            topLeft = Offset(faceLeft, faceTop),
            size = Size(faceSize, faceSize * 1.04f)
        )
        drawOval(
            color = Color(0xFF263858),
            topLeft = Offset(faceLeft + faceSize * 0.07f, faceTop + faceSize * 0.08f),
            size = Size(faceSize * 0.86f, faceSize * 0.84f),
            style = Stroke(width = unit * 0.012f)
        )
        val eyeY = faceTop + faceSize * 0.43f
        val eyeW = faceSize * 0.20f
        val eyeH = faceSize * 0.12f * blink.coerceAtLeast(0.06f)
        val leftEye = Offset(c.x - faceSize * 0.23f, eyeY)
        val rightEye = Offset(c.x + faceSize * 0.23f, eyeY)
        listOf(leftEye, rightEye).forEach { eye ->
            drawOval(Color(0xFF17233F), Offset(eye.x - eyeW / 2f, eye.y - eyeH / 2f), Size(eyeW, eyeH))
            if (blink > 0.25f) {
                drawCircle(Color(0xFF73E6E0), radius = faceSize * 0.043f, center = Offset(eye.x, eye.y))
                drawCircle(Color(0xFF10182B), radius = faceSize * 0.021f, center = Offset(eye.x, eye.y))
                drawCircle(Color.White.copy(alpha = 0.85f), radius = faceSize * 0.012f, center = Offset(eye.x - faceSize * 0.015f, eye.y - faceSize * 0.018f))
            }
        }
        val mouthWidth = faceSize * 0.22f
        val mouthHeight = faceSize * (0.018f + mouthOpen * 0.075f)
        drawOval(
            color = Color(0xFF243653),
            topLeft = Offset(c.x - mouthWidth / 2f, faceTop + faceSize * 0.70f - mouthHeight / 2f),
            size = Size(mouthWidth, mouthHeight)
        )
        drawCircle(Color(0xFF9EEBE5).copy(alpha = 0.6f), radius = unit * 0.018f, center = Offset(c.x, faceTop + faceSize * 0.57f))
    }
}

@Composable
private fun SettingsDialog(prefs: YaraPreferences, close: () -> Unit) {
    var base by remember { mutableStateOf(prefs.baseUrl) }
    var model by remember { mutableStateOf(prefs.model) }
    var key by remember { mutableStateOf(prefs.apiKey) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("اتصال مدل هوش مصنوعی") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(base, { base = it }, label = { Text("نشانی پایه API") }, singleLine = true)
                OutlinedTextField(model, { model = it }, label = { Text("نام مدل") }, singleLine = true)
                OutlinedTextField(key, { key = it }, label = { Text("کلید API") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                Text("کلید API فعلاً در حافظه محلی برنامه ذخیره می‌شود؛ پیش از انتشار عمومی باید ذخیره‌سازی رمزنگاری شود.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { prefs.save(base.trim(), model.trim(), key.trim()); close() }) { Text("ذخیره") } },
        dismissButton = { TextButton(onClick = close) { Text("انصراف") } }
    )
}
