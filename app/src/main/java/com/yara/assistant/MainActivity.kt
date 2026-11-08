package com.yara.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

data class ChatLine(val role: String, val content: String)

class MainActivity : ComponentActivity() {
    private val prefs by lazy { YaraPreferences(this) }
    private val store by lazy { ChatStore(this) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val saved = store.load()
            setContent {
                MaterialTheme {
                    var lines by remember { mutableStateOf(saved.ifEmpty { listOf(ChatLine("assistant", "سلام، من یارا هستم. برای شروع پیام بده یا تنظیمات اتصال مدل را باز کن.")) }) }
                    var input by remember { mutableStateOf("") }
                    var busy by remember { mutableStateOf(false) }
                    var showSettings by remember { mutableStateOf(false) }
                    val listState = rememberLazyListState()
                    Scaffold(topBar = {
                        TopAppBarCompat(title = "یارا", onSettings = { showSettings = true })
                    }) { pad ->
                        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
                            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(lines) { line ->
                                    Card(Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(12.dp)) {
                                            Text(if (line.role == "user") "شما" else "یارا", style = MaterialTheme.typography.labelMedium)
                                            Spacer(Modifier.height(4.dp))
                                            Text(line.content)
                                        }
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("پیام خود را بنویسید…") }, enabled = !busy)
                                Button(enabled = input.isNotBlank() && !busy, onClick = {
                                    val msg = input.trim(); input = ""
                                    lines = lines + ChatLine("user", msg); busy = true
                                    lifecycleScope.launch {
                                        store.save(lines)
                                        val reply = runCatching { YaraProvider(prefs).reply(lines.dropLast(1), msg) }
                                            .getOrElse { "اتصال به مدل انجام نشد: ${it.message}\nاز تنظیمات، نشانی سرویس، نام مدل و کلید API را بررسی کن." }
                                        lines = lines + ChatLine("assistant", reply)
                                        store.save(lines); busy = false
                                    }
                                }) { Text(if (busy) "…" else "ارسال") }
                            }
                        }
                    }
                    if (showSettings) SettingsDialog(prefs) { showSettings = false }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun TopAppBarCompat(title: String, onSettings: () -> Unit) {
    TopAppBar(title = { Text(title) }, actions = { TextButton(onClick = onSettings) { Text("تنظیمات") } })
}

@Composable private fun SettingsDialog(prefs: YaraPreferences, close: () -> Unit) {
    var base by remember { mutableStateOf(prefs.baseUrl) }
    var model by remember { mutableStateOf(prefs.model) }
    var key by remember { mutableStateOf(prefs.apiKey) }
    AlertDialog(onDismissRequest = close, title = { Text("اتصال مدل هوش مصنوعی") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("سرویس‌های سازگار با قالب OpenAI API")
            OutlinedTextField(base, { base = it }, label = { Text("نشانی پایه API") }, singleLine = true)
            OutlinedTextField(model, { model = it }, label = { Text("نام مدل") }, singleLine = true)
            OutlinedTextField(key, { key = it }, label = { Text("کلید API") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            Text("کلید فقط روی همین دستگاه ذخیره می‌شود. از سرویس‌دهنده معتبر استفاده کن.", style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onClick = { prefs.save(base.trim(), model.trim(), key.trim()); close() }) { Text("ذخیره") } },
        dismissButton = { TextButton(onClick = close) { Text("انصراف") } })
}
