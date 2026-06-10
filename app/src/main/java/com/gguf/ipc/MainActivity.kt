package com.gguf.ipc

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

// ── Palette ──────────────────────────────────────────────────────────
private object Pal {
    val Bg        = Color(0xFF0A0A0F)
    val Surface   = Color(0xFF141420)
    val Card      = Color(0xFF1A1A2E)
    val CardLight = Color(0xFF22223A)
    val Border    = Color(0xFF2A2A40)
    val Accent    = Color(0xFF6C63FF)
    val Accent2   = Color(0xFF00D9A6)
    val Red       = Color(0xFFFF4757)
    val Amber     = Color(0xFFFFBE0B)
    val Purple    = Color(0xFFBB86FC)
    val Teal      = Color(0xFF03DAC6)
    val Text      = Color(0xFFEAEAEE)
    val Text2     = Color(0xFF9898AA)
    val Text3     = Color(0xFF5C5C72)
    val UserBg    = Color(0xFF2D2B55)
    val BotBg     = Color(0xFF1A1A2E)
    val ThinkBg   = Color(0xFF1E1A33)
    val GradientStart = Color(0xFF6C63FF)
    val GradientEnd   = Color(0xFF00D9A6)
}

// ── Data ─────────────────────────────────────────────────────────────
enum class Screen { CHAT, SETTINGS, INFO }
data class ChatMessage(
    val role: Role,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val tps: Float = 0f,
    val tokens: Int = 0
)
enum class Role { USER, ASSISTANT }

// ── Activity ─────────────────────────────────────────────────────────
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EngineCore.bootZeroCopyEngine()
        EngineManager.init(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                background = Pal.Bg, surface = Pal.Surface,
                primary = Pal.Accent, onBackground = Pal.Text, onSurface = Pal.Text
            )) {
                AppScaffold()
            }
        }
    }

    fun copyUriToFiles(uri: Uri, filename: String, onProgress: (String) -> Unit): String? = try {
        val cacheFile = File(filesDir, filename)
        contentResolver.openInputStream(uri)?.use { input: InputStream ->
            onProgress("Copying model...")
            cacheFile.outputStream().use { input.copyTo(it, bufferSize = 8 * 1024 * 1024) }
        }
        cacheFile.absolutePath
    } catch (e: Exception) { null }
}

// ── Root Scaffold ────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold() {
    val activity = LocalContext.current as MainActivity
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val clip = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    var screen       by remember { mutableStateOf(Screen.CHAT) }
    var modelLoaded  by remember { mutableStateOf(false) }
    var isLoading    by remember { mutableStateOf(false) }
    var isInferring  by remember { mutableStateOf(false) }
    var filename     by remember { mutableStateOf("") }
    var modelInfo    by remember { mutableStateOf<JSONObject?>(null) }
    var streamedText by remember { mutableStateOf("") }
    var prompt       by remember { mutableStateOf("") }
    var kvUsage      by remember { mutableIntStateOf(0) }
    var tps          by remember { mutableFloatStateOf(0f) }
    var totalTokens  by remember { mutableIntStateOf(0) }
    var statusText   by remember { mutableStateOf("No model loaded") }
    var showSettings by remember { mutableStateOf(false) }
    var showInfo     by remember { mutableStateOf(false) }
    val chat = remember { mutableStateListOf<ChatMessage>() }

    // Settings fields
    var sNCtx      by remember { mutableStateOf(SettingsManager.nCtx.toString()) }
    var sMaxTok    by remember { mutableStateOf(SettingsManager.maxTokens.toString()) }
    var sTemp      by remember { mutableStateOf(SettingsManager.temperature.toString()) }
    var sTopP      by remember { mutableStateOf(SettingsManager.topP.toString()) }
    var sMinP      by remember { mutableStateOf(SettingsManager.minP.toString()) }
    var sGpu       by remember { mutableStateOf(SettingsManager.gpuLayers.toString()) }
    var sThreads   by remember { mutableStateOf(SettingsManager.threads.toString()) }
    var sRepPen    by remember { mutableStateOf(SettingsManager.repeatPenalty.toString()) }
    var sFreqPen   by remember { mutableStateOf(SettingsManager.freqPenalty.toString()) }
    var sPresPen   by remember { mutableStateOf(SettingsManager.presPenalty.toString()) }
    var sSysPrompt by remember { mutableStateOf(SettingsManager.systemPrompt) }
    var benchRes   by remember { mutableStateOf("") }
    var isBenching by remember { mutableStateOf(false) }

    val engine = remember(modelLoaded) {
        if (modelLoaded) EngineManager.getCurrentEngine() else null
    }

    fun applySettings() {
        val cfg = InferenceEngine.Config(
            nCtx = sNCtx.toIntOrNull()?.coerceIn(512, 32768) ?: 8192,
            maxNewTokens = sMaxTok.toIntOrNull()?.coerceIn(64, 8192) ?: 4096,
            temperature = sTemp.toFloatOrNull()?.coerceIn(0f, 2f) ?: 0.7f,
            topP = sTopP.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.9f,
            minP = sMinP.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.05f,
            nGpuLayers = sGpu.toIntOrNull()?.coerceIn(0, 999) ?: 99,
            nThreads = sThreads.toIntOrNull()?.coerceIn(1, 16) ?: 4, seed = -1
        )
        engine?.setConfig(cfg)
        engine?.setSystemPrompt(sSysPrompt)
        engine?.setRepeatPenalty(InferenceEngine.RepeatPenaltyConfig(
            sRepPen.toFloatOrNull() ?: 1.1f, sFreqPen.toFloatOrNull() ?: 0f, sPresPen.toFloatOrNull() ?: 0f
        ))
        SettingsManager.nCtx = cfg.nCtx; SettingsManager.maxTokens = cfg.maxNewTokens
        SettingsManager.temperature = cfg.temperature; SettingsManager.topP = cfg.topP
        SettingsManager.minP = cfg.minP; SettingsManager.gpuLayers = cfg.nGpuLayers
        SettingsManager.threads = cfg.nThreads
        SettingsManager.repeatPenalty = sRepPen.toFloatOrNull() ?: 1.1f
        SettingsManager.freqPenalty = sFreqPen.toFloatOrNull() ?: 0f
        SettingsManager.presPenalty = sPresPen.toFloatOrNull() ?: 0f
        SettingsManager.systemPrompt = sSysPrompt
    }

    // Polling stream
    LaunchedEffect(isInferring) {
        if (!isInferring) return@LaunchedEffect
        val start = System.currentTimeMillis()
        while (isInferring) {
            delay(80)
            val e = EngineManager.getCurrentEngine() ?: break
            val partial = e.readPartialStream()
            if (partial.isNotEmpty()) streamedText = partial
            val elapsed = (System.currentTimeMillis() - start) / 1000f
            val tok = e.getTokensGenerated()
            if (elapsed > 0) tps = tok / elapsed
            kvUsage = e.getKvCacheUsage()
            if (e.isInferenceDone()) {
                delay(30)
                val final = e.readTokenStream()
                val ft = e.getTokensGenerated()
                totalTokens += ft
                if (final.isNotEmpty()) chat.add(ChatMessage(Role.ASSISTANT, final, tps = if (elapsed > 0) ft / elapsed else 0f, tokens = ft))
                streamedText = ""; isInferring = false
            }
        }
    }

    LaunchedEffect(chat.size, isInferring) {
        if (chat.isNotEmpty()) listState.animateScrollToItem(chat.size - 1)
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "model.gguf"
                isLoading = true; modelLoaded = false; streamedText = ""; statusText = "Copying..."
                scope.launch(Dispatchers.IO) {
                    val path = activity.copyUriToFiles(uri, name) { msg -> scope.launch(Dispatchers.Main) { statusText = msg } }
                    if (path == null) { withContext(Dispatchers.Main) { statusText = "Copy failed"; isLoading = false }; return@launch }
                    withContext(Dispatchers.Main) { statusText = "Loading..." }
                    val eng = EngineManager.getEngineForFormat(path)
                    eng.setConfig(SettingsManager.toConfig()); eng.setRepeatPenalty(SettingsManager.toRepeatPenaltyConfig()); eng.setSystemPrompt(SettingsManager.systemPrompt)
                    val ok = eng.loadModel(path)
                    if (ok) modelInfo = eng.getModelInfo()
                    withContext(Dispatchers.Main) {
                        isLoading = false; modelLoaded = ok; filename = name
                        statusText = if (ok) "${eng.engineName} \u00B7 $name" else "Load failed"
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("ZC", fontWeight = FontWeight.Black, fontSize = 18.sp,
                            fontFamily = FontFamily.Monospace, color = Pal.Accent)
                        Text(statusText, fontSize = 10.sp, color = if (modelLoaded) Pal.Accent2 else Pal.Text3,
                            fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    if (modelLoaded && isInferring) {
                        AssistChip(onClick = { engine?.abortInference() }, label = { Text("Stop", fontSize = 11.sp) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = Pal.Red.copy(alpha = 0.2f), labelColor = Pal.Red))
                    }
                    if (modelLoaded) {
                        AssistChip(onClick = { showInfo = true }, label = { Text("${kvUsage}%", fontSize = 11.sp) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = if (kvUsage > 80) Pal.Red.copy(alpha = 0.2f) else Pal.Accent2.copy(alpha = 0.15f), labelColor = if (kvUsage > 80) Pal.Red else Pal.Accent2))
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Outlined.Tune, "Settings", tint = Pal.Text2)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Pal.Bg)
            )
        },
        containerColor = Pal.Bg
    ) { pad ->
        Column(modifier = Modifier.padding(pad).fillMaxSize()) {
            // Content area
            Box(modifier = Modifier.weight(1f)) {
                if (!modelLoaded && !isLoading) {
                    WelcomeScreen(onLoad = {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "*/*"))
                        }
                        filePicker.launch(intent)
                    })
                } else {
                    ChatList(chat, listState, streamedText, isInferring, clip)
                }
            }

            // Bench bar (when on bench tab)
            if (isBenching) {
                Surface(color = Pal.Card, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Pal.Amber, strokeWidth = 1.5.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Benchmarking...", color = Pal.Text2, fontSize = 12.sp)
                    }
                }
            }

            // Input bar
            if (modelLoaded) {
                InputBar(
                    prompt = prompt, onPromptChange = { prompt = it },
                    isInferring = isInferring,
                    onSend = {
                        if (prompt.isNotBlank()) {
                            val msg = prompt; prompt = ""; streamedText = ""; isInferring = true
                            chat.add(ChatMessage(Role.USER, msg))
                            scope.launch(Dispatchers.IO) { engine?.executeInference(msg) }
                        }
                    },
                    onStop = { engine?.abortInference() },
                    onImage = { /* TODO: vision model support */ },
                    onBench = {
                        if (!isInferring) {
                            isBenching = true
                            scope.launch(Dispatchers.IO) {
                                val raw = engine?.benchmark(512, 128)
                                withContext(Dispatchers.Main) { benchRes = raw?.toString() ?: "{}"; isBenching = false }
                            }
                        }
                    }
                )
            }
        }
    }

    // Settings sheet
    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }, containerColor = Pal.Card) {
            SettingsContent(sNCtx, { sNCtx = it }, sMaxTok, { sMaxTok = it }, sTemp, { sTemp = it },
                sTopP, { sTopP = it }, sMinP, { sMinP = it }, sGpu, { sGpu = it }, sThreads, { sThreads = it },
                sRepPen, { sRepPen = it }, sFreqPen, { sFreqPen = it }, sPresPen, { sPresPen = it },
                sSysPrompt, { sSysPrompt = it }, onApply = { applySettings(); showSettings = false },
                onAutoDetect = {
                    val info = EngineManager.getDeviceInfo()
                    if (info != null) { SettingsManager.applyToDeviceDefaults(info); sNCtx = SettingsManager.nCtx.toString(); sGpu = SettingsManager.gpuLayers.toString(); sThreads = SettingsManager.threads.toString() }
                },
                onUnload = {
                    engine?.unloadModel(); modelLoaded = false; filename = ""; modelInfo = null; statusText = "Unloaded"
                    showSettings = false
                },
                onReset = { engine?.resetContext(); chat.clear(); streamedText = ""; statusText = "Context reset" },
                benchRes = benchRes, isBenching = isBenching
            )
        }
    }

    // Info sheet
    if (showInfo) {
        ModalBottomSheet(onDismissRequest = { showInfo = false }, containerColor = Pal.Card) {
            InfoContent(modelInfo, filename, totalTokens)
        }
    }
}

// ── Welcome Screen ───────────────────────────────────────────────────
@Composable
fun WelcomeScreen(onLoad: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.size(80.dp).clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Pal.GradientStart, Pal.GradientEnd))),
            contentAlignment = Alignment.Center) {
            Text("ZC", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.White, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(24.dp))
        Text("ZeroCopy", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Pal.Text, fontFamily = FontFamily.Monospace)
        Text("Private on-device LLM inference", fontSize = 13.sp, color = Pal.Text2)
        Spacer(Modifier.height(8.dp))
        Text("llama.cpp \u00B7 MNN \u00B7 LiteRT-LM", fontSize = 11.sp, color = Pal.Text3, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(40.dp))
        Button(onClick = onLoad, modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Pal.Accent)) {
            Icon(Icons.Outlined.FolderOpen, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Load Model", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
        }
    }
}

// ── Chat List ────────────────────────────────────────────────────────
@Composable
fun ChatList(chat: List<ChatMessage>, listState: LazyListState, streamedText: String, isInferring: Boolean, clip: ClipboardManager) {
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(chat) { msg ->
            ChatBubble(msg, onCopy = { clip.setPrimaryClip(ClipData.newPlainText("msg", it)) })
        }
        if (isInferring && streamedText.isNotEmpty()) {
            item { StreamingBubble(streamedText) }
        }
    }
}

// ── Chat Bubble ──────────────────────────────────────────────────────
@Composable
fun ChatBubble(msg: ChatMessage, onCopy: (String) -> Unit) {
    val isUser = msg.role == Role.USER
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom) {
        if (!isUser) {
            Box(modifier = Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(Pal.Accent), contentAlignment = Alignment.Center) {
                Text("Z", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.width(8.dp))
        }
        Column(modifier = Modifier.widthIn(max = 300.dp)) {
            Surface(modifier = Modifier.clip(RoundedCornerShape(
                topStart = if (isUser) 18.dp else 6.dp, topEnd = if (isUser) 6.dp else 18.dp,
                bottomStart = 18.dp, bottomEnd = 18.dp
            )).clickable { onCopy(msg.content) }, color = if (isUser) Pal.UserBg else Pal.Card,
                shape = RoundedCornerShape(0.dp)) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (!isUser) ThinkingContent(msg.content)
                    else Text(msg.content, color = Pal.Text, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
            if (!isUser && msg.tps > 0) {
                Text("${"%.1f".format(msg.tps)} t/s \u00B7 ${msg.tokens} tok",
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                    fontSize = 9.sp, color = Pal.Text3, fontFamily = FontFamily.Monospace)
            }
        }
        if (isUser) {
            Spacer(Modifier.width(8.dp))
            Box(modifier = Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(Pal.Accent2), contentAlignment = Alignment.Center) {
                Text("U", fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ── Thinking Content ─────────────────────────────────────────────────
@Composable
fun ThinkingContent(content: String) {
    val pattern = remember { Regex("(?:<think>|<think>\\n?)(.*?)(?:</think>|</think>\\n?)", RegexOption.DOT_MATCHES_ALL) }
    val match = remember(content) { pattern.find(content) }
    if (match != null) {
        val think = match.groupValues[1].trim()
        val rest = content.substring(match.range.last + 1).trim()
        var open by remember { mutableStateOf(false) }
        Column {
            Surface(onClick = { open = !open }, shape = RoundedCornerShape(8.dp), color = Pal.ThinkBg,
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Lightbulb, null, modifier = Modifier.size(12.dp), tint = Pal.Purple)
                        Spacer(Modifier.width(4.dp))
                        Text(if (open) "Thinking" else "Thinking...", fontSize = 10.sp, color = Pal.Purple,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                    }
                    AnimatedVisibility(open) { Text(think, modifier = Modifier.padding(top = 4.dp), fontSize = 11.sp, color = Pal.Text3, lineHeight = 15.sp) }
                }
            }
            if (rest.isNotEmpty()) Text(rest, color = Pal.Text, fontSize = 14.sp, lineHeight = 20.sp)
        }
    } else {
        Text(content, color = Pal.Text, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

// ── Streaming Bubble ─────────────────────────────────────────────────
@Composable
fun StreamingBubble(text: String) {
    val thinking = remember(text) { text.contains("<think>") && !text.contains("</think>") }
    val dots = rememberInfiniteTransition(label = "d").animateFloat(0f, 3f, infiniteRepeatable(tween(1200)), label = "d")

    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.Bottom) {
        Box(modifier = Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(Pal.Accent), contentAlignment = Alignment.Center) {
            Text("Z", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.width(8.dp))
        Surface(modifier = Modifier.clip(RoundedCornerShape(6.dp, 18.dp, 18.dp, 18.dp)), color = Pal.Card) {
            if (thinking) {
                Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Lightbulb, null, modifier = Modifier.size(14.dp), tint = Pal.Purple)
                    Spacer(Modifier.width(6.dp))
                    Text("Thinking", fontSize = 12.sp, color = Pal.Purple, fontFamily = FontFamily.Monospace)
                }
            } else {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    val pat = remember { Regex("(?:<think>|<think>\\n?)(.*?)(?:</think>|</think>\\n?)", RegexOption.DOT_MATCHES_ALL) }
                    val m = remember(text) { pat.find(text) }
                    if (m != null) {
                        val think = m.groupValues[1].trim()
                        val rest = text.substring(m.range.last + 1).trim()
                        Surface(onClick = {}, shape = RoundedCornerShape(8.dp), color = Pal.ThinkBg, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                            Column(modifier = Modifier.padding(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Lightbulb, null, modifier = Modifier.size(10.dp), tint = Pal.Purple)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Thinking...", fontSize = 9.sp, color = Pal.Purple, fontFamily = FontFamily.Monospace)
                                }
                                Text(think, fontSize = 10.sp, color = Pal.Text3, lineHeight = 14.sp, modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                        if (rest.isNotEmpty()) Text(rest, color = Pal.Text, fontSize = 14.sp, lineHeight = 20.sp)
                    } else {
                        Text(text, color = Pal.Text, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                    Spacer(Modifier.width(2.dp))
                    Box(modifier = Modifier.padding(top = 2.dp).size(6.dp).clip(CircleShape).background(Pal.Accent).alpha(0.6f))
                }
            }
        }
    }
}

// ── Input Bar ────────────────────────────────────────────────────────
@Composable
fun InputBar(prompt: String, onPromptChange: (String) -> Unit, isInferring: Boolean,
             onSend: () -> Unit, onStop: () -> Unit, onImage: () -> Unit, onBench: () -> Unit) {
    Surface(color = Pal.Surface, shadowElevation = 8.dp) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            OutlinedTextField(value = prompt, onValueChange = onPromptChange, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Message...", color = Pal.Text3, fontSize = 14.sp) },
                enabled = !isInferring, maxLines = 5,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Pal.Accent.copy(alpha = 0.5f), unfocusedBorderColor = Pal.Border,
                    focusedContainerColor = Pal.Card, unfocusedContainerColor = Pal.Card,
                    focusedTextColor = Pal.Text, unfocusedTextColor = Pal.Text, cursorColor = Pal.Accent
                ),
                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp))
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onImage, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Image, "Image", tint = Pal.Purple, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onBench, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Speed, "Benchmark", tint = Pal.Amber, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.weight(1f))
                val enabled = prompt.isNotBlank() && !isInferring
                FilledIconButton(onClick = if (isInferring) onStop else onSend, enabled = enabled || isInferring,
                    modifier = Modifier.size(40.dp), shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isInferring) Pal.Red else Pal.Accent,
                        disabledContainerColor = Pal.Card
                    )) {
                    if (isInferring) Icon(Icons.Filled.Stop, "Stop", tint = Color.White, modifier = Modifier.size(18.dp))
                    else Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = if (enabled) Color.White else Pal.Text3, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

// ── Settings Sheet ───────────────────────────────────────────────────
@Composable
fun SettingsContent(
    nCtx: String, onNCtx: (String) -> Unit, maxTok: String, onMaxTok: (String) -> Unit,
    temp: String, onTemp: (String) -> Unit, topP: String, onTopP: (String) -> Unit,
    minP: String, onMinP: (String) -> Unit, gpu: String, onGpu: (String) -> Unit,
    threads: String, onThreads: (String) -> Unit, repPen: String, onRepPen: (String) -> Unit,
    freqPen: String, onFreqPen: (String) -> Unit, presPen: String, onPresPen: (String) -> Unit,
    sysPrompt: String, onSysPrompt: (String) -> Unit,
    onApply: () -> Unit, onAutoDetect: () -> Unit, onUnload: () -> Unit, onReset: () -> Unit,
    benchRes: String, isBenching: Boolean
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Pal.Text)
        Button(onClick = onAutoDetect, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Pal.Purple)) {
            Icon(Icons.Outlined.Memory, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
            Text("Auto-Detect Device", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        SettingField("Context Window", "512-32768", nCtx, onNCtx)
        SettingField("Max Tokens", "64-8192", maxTok, onMaxTok)
        SettingField("GPU Layers", "99=GPU, 0=CPU", gpu, onGpu)
        SettingField("Threads", "1-16", threads, onThreads)
        SettingField("Temperature", "0-2", temp, onTemp)
        SettingField("Top-P", "0-1", topP, onTopP)
        SettingField("Min-P", "0-1", minP, onMinP)
        SettingField("Repeat Penalty", "1.0=off", repPen, onRepPen)
        SettingField("Freq Penalty", "0=off", freqPen, onFreqPen)
        SettingField("Presence Penalty", "0=off", presPen, onPresPen)
        OutlinedTextField(value = sysPrompt, onValueChange = onSysPrompt, modifier = Modifier.fillMaxWidth(),
            label = { Text("System Prompt", fontSize = 12.sp) }, maxLines = 4,
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Pal.Accent, unfocusedBorderColor = Pal.Border,
                focusedTextColor = Pal.Text, unfocusedTextColor = Pal.Text, cursorColor = Pal.Accent),
            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace))

        // Benchmark result
        if (benchRes.isNotEmpty()) {
            Card(shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = Pal.CardLight)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Benchmark", fontSize = 12.sp, color = Pal.Amber, fontWeight = FontWeight.Bold)
                    Text(benchRes, fontSize = 11.sp, color = Pal.Text2, fontFamily = FontFamily.Monospace)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onApply, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Pal.Accent)) {
                Text("Apply", color = Color.White, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Pal.Amber)) {
                Text("Reset Context", fontSize = 11.sp)
            }
        }
        OutlinedButton(onClick = onUnload, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Pal.Red)) {
            Text("Unload Model", fontSize = 11.sp)
        }
        Text("Context/GPU changes need model reload.", fontSize = 10.sp, color = Pal.Amber, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun SettingField(label: String, hint: String, value: String, onChange: (String) -> Unit) {
    Column {
        Text(label, fontSize = 11.sp, color = Pal.Text2)
        Text(hint, fontSize = 9.sp, color = Pal.Text3)
        OutlinedTextField(value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth().height(52.dp),
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Pal.Accent, unfocusedBorderColor = Pal.Border,
                focusedTextColor = Pal.Text, unfocusedTextColor = Pal.Text, cursorColor = Pal.Accent),
            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, fontFamily = FontFamily.Monospace))
    }
}

// ── Info Sheet ───────────────────────────────────────────────────────
@Composable
fun InfoContent(modelInfo: JSONObject?, filename: String, totalTokens: Int) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Info", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Pal.Text)
        if (modelInfo != null) {
            Card(shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = Pal.CardLight)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Model: $filename", fontSize = 12.sp, color = Pal.Accent, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    val keys = modelInfo.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                            Text(k, fontSize = 10.sp, color = Pal.Text3, modifier = Modifier.weight(1f))
                            Text(modelInfo.optString(k, "\u2014"), fontSize = 10.sp, color = Pal.Text2, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
        Card(shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = Pal.CardLight)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Supported Formats", fontSize = 12.sp, color = Pal.Accent, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                FormatRow("GGUF", "llama.cpp", "MIT", "Vulkan/OpenCL/CPU")
                FormatRow("MNN", "MNN-LLM", "Apache 2.0", "Optimized CPU")
                FormatRow("TFLite", "LiteRT-LM", "Apache 2.0", "GPU/NPU/CPU")
            }
        }
        Card(shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = Pal.CardLight)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Performance", fontSize = 12.sp, color = Pal.Accent2, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("\u2022 Big core pinning (sched_setaffinity)\n\u2022 Priority boost (-20)\n\u2022 RAM lock (mlockall)\n\u2022 ThinLTO + armv8.7a\n\u2022 Flash attention enabled",
                    fontSize = 11.sp, color = Pal.Text2, lineHeight = 16.sp)
            }
        }
        Card(shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = Pal.CardLight)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Session", fontSize = 12.sp, color = Pal.Purple, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("Tokens generated: $totalTokens", fontSize = 12.sp, color = Pal.Text2)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun FormatRow(format: String, engine: String, license: String, note: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(format, fontSize = 11.sp, color = Pal.Accent, fontWeight = FontWeight.Bold, modifier = Modifier.width(70.dp))
        Text(engine, fontSize = 11.sp, color = Pal.Text, modifier = Modifier.width(70.dp))
        Text(license, fontSize = 10.sp, color = Pal.Text3, modifier = Modifier.width(70.dp))
        Text(note, fontSize = 10.sp, color = Pal.Text2)
    }
}
