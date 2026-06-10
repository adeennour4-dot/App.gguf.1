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
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────────────────────────────────────
// Design tokens
// ─────────────────────────────────────────────────────────────────────────────
private val BgDeep      = Color(0xFF080C14)
private val BgSurface   = Color(0xFF0F172A)
private val BgCard      = Color(0xFF111827)
private val BgInput     = Color(0xFF1E293B)
private val AccentCyan  = Color(0xFF22D3EE)
private val AccentGreen = Color(0xFF34D399)
private val AccentAmber = Color(0xFFFBBF24)
private val AccentRed   = Color(0xFFF87171)
private val AccentPurple= Color(0xFFA78BFA)
private val TextPrimary = Color(0xFFF0F4F8)
private val TextSecond  = Color(0xFF94A3B8)
private val TextMuted   = Color(0xFF64748B)
private val ThinkBg     = Color(0xFF0F172A)
private val UserBubble  = Color(0xFF1E3A5F)
private val BotBubble   = Color(0xFF14291A)

// ─────────────────────────────────────────────────────────────────────────────
// Data classes
// ─────────────────────────────────────────────────────────────────────────────
enum class Screen { CHAT, MODELS, SETTINGS, INFO, BENCH }
enum class Role { USER, ASSISTANT }
data class ChatMessage(
    val role: Role,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val tokensPerSec: Float = 0f,
    val tokenCount: Int = 0
)

// ─────────────────────────────────────────────────────────────────────────────
// Activity
// ─────────────────────────────────────────────────────────────────────────────
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EngineCore.bootZeroCopyEngine()
        EngineManager.init(this)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background   = BgDeep,
                    surface      = BgCard,
                    primary      = AccentCyan,
                    onBackground = TextPrimary,
                    onSurface    = TextPrimary
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = BgDeep) {
                    AppRoot()
                }
            }
        }
    }

    fun copyUriToFiles(uri: Uri, filename: String, onProgress: (String) -> Unit): String? = try {
        val cacheFile = File(filesDir, filename)
        contentResolver.openInputStream(uri)?.use { input: InputStream ->
            onProgress("Copying model to internal storage...")
            cacheFile.outputStream().use { input.copyTo(it, bufferSize = 8 * 1024 * 1024) }
        }
        cacheFile.absolutePath
    } catch (e: Exception) { null }
}

// ─────────────────────────────────────────────────────────────────────────────
// Main App Root with Bottom Navigation
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val activity       = LocalContext.current as MainActivity
    val coroutineScope = rememberCoroutineScope()
    val listState      = rememberLazyListState()
    val clipManager    = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    // Model state
    var engineStatus  by remember { mutableStateOf("No model loaded") }
    var isLoading     by remember { mutableStateOf(false) }
    var isInferring   by remember { mutableStateOf(false) }
    var modelLoaded   by remember { mutableStateOf(false) }
    var modelFilename by remember { mutableStateOf("") }
    var modelInfo     by remember { mutableStateOf<JSONObject?>(null) }
    var streamedText  by remember { mutableStateOf("") }
    var promptInput   by remember { mutableStateOf("") }
    var kvUsage       by remember { mutableStateOf(0) }
    var tokensPerSec  by remember { mutableStateOf(0f) }
    var inferStartMs  by remember { mutableStateOf(0L) }
    var totalTokens   by remember { mutableStateOf(0) }

    // Chat & sessions
    val chatHistory = remember { mutableStateListOf<ChatMessage>() }
    var sessions by remember { mutableStateOf(ChatManager.getSessions()) }
    var currentSessionId by remember { mutableStateOf<String?>(null) }

    // Navigation
    var selectedScreen by remember { mutableStateOf(Screen.CHAT) }

    // Settings (persisted)
    var nCtxStr        by remember { mutableStateOf(SettingsManager.nCtx.toString()) }
    var maxTokensStr   by remember { mutableStateOf(SettingsManager.maxTokens.toString()) }
    var tempStr        by remember { mutableStateOf(SettingsManager.temperature.toString()) }
    var topPStr        by remember { mutableStateOf(SettingsManager.topP.toString()) }
    var minPStr        by remember { mutableStateOf(SettingsManager.minP.toString()) }
    var gpuLayersStr   by remember { mutableStateOf(SettingsManager.gpuLayers.toString()) }
    var nThreadsStr    by remember { mutableStateOf(SettingsManager.threads.toString()) }
    var repeatPenStr   by remember { mutableStateOf(SettingsManager.repeatPenalty.toString()) }
    var freqPenStr     by remember { mutableStateOf(SettingsManager.freqPenalty.toString()) }
    var presPenStr     by remember { mutableStateOf(SettingsManager.presPenalty.toString()) }
    var systemPrompt   by remember { mutableStateOf(SettingsManager.systemPrompt) }

    // Benchmark
    var benchResult    by remember { mutableStateOf("") }
    var isBenching     by remember { mutableStateOf(false) }

    // Get current engine
    val currentEngine = remember(modelLoaded) {
        if (modelLoaded) EngineManager.getCurrentEngine() else null
    }

    fun applySettings() {
        val cfg = InferenceEngine.Config(
            nCtx         = nCtxStr.toIntOrNull()?.coerceIn(512, 32768) ?: 8192,
            maxNewTokens = maxTokensStr.toIntOrNull()?.coerceIn(64, 8192) ?: 4096,
            temperature  = tempStr.toFloatOrNull()?.coerceIn(0f, 2f) ?: 0.7f,
            topP         = topPStr.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.9f,
            minP         = minPStr.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.05f,
            nGpuLayers   = gpuLayersStr.toIntOrNull()?.coerceIn(0, 999) ?: 99,
            nThreads     = nThreadsStr.toIntOrNull()?.coerceIn(1, 16) ?: 4,
            seed         = -1
        )
        currentEngine?.setConfig(cfg)
        currentEngine?.setSystemPrompt(systemPrompt)
        currentEngine?.setRepeatPenalty(InferenceEngine.RepeatPenaltyConfig(
            repeatPenStr.toFloatOrNull() ?: 1.1f,
            freqPenStr.toFloatOrNull()   ?: 0.0f,
            presPenStr.toFloatOrNull()   ?: 0.0f
        ))
        // Persist
        SettingsManager.nCtx = cfg.nCtx
        SettingsManager.maxTokens = cfg.maxNewTokens
        SettingsManager.temperature = cfg.temperature
        SettingsManager.topP = cfg.topP
        SettingsManager.minP = cfg.minP
        SettingsManager.gpuLayers = cfg.nGpuLayers
        SettingsManager.threads = cfg.nThreads
        SettingsManager.repeatPenalty = repeatPenStr.toFloatOrNull() ?: 1.1f
        SettingsManager.freqPenalty = freqPenStr.toFloatOrNull() ?: 0.0f
        SettingsManager.presPenalty = presPenStr.toFloatOrNull() ?: 0.0f
        SettingsManager.systemPrompt = systemPrompt
    }

    // Stream polling
    LaunchedEffect(isInferring) {
        if (isInferring) {
            inferStartMs = System.currentTimeMillis()
            while (isInferring) {
                delay(80)
                val engine = EngineManager.getCurrentEngine() ?: break
                val partial = engine.readPartialStream()
                if (partial.isNotEmpty()) streamedText = partial
                val elapsed = (System.currentTimeMillis() - inferStartMs) / 1000f
                val toks = engine.getTokensGenerated()
                if (elapsed > 0) tokensPerSec = toks / elapsed
                kvUsage = engine.getKvCacheUsage()

                if (engine.isInferenceDone()) {
                    delay(20)
                    val finalText = engine.readTokenStream()
                    val finalToks = engine.getTokensGenerated()
                    val finalTps = if (elapsed > 0) finalToks / elapsed else 0f
                    totalTokens += finalToks
                    if (finalText.isNotEmpty()) {
                        chatHistory.add(ChatMessage(Role.ASSISTANT, finalText, tokensPerSec = finalTps, tokenCount = finalToks))
                        currentSessionId?.let { sid ->
                            ChatManager.addMessage(sid, ChatManager.Message("assistant", finalText, tokensPerSec = finalTps, tokenCount = finalToks))
                        }
                    }
                    streamedText = ""
                    isInferring = false
                }
            }
        }
    }

    LaunchedEffect(chatHistory.size, isInferring) {
        if (chatHistory.isNotEmpty())
            listState.animateScrollToItem(chatHistory.size - 1)
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val filename = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "model.gguf"
                isLoading = true; modelLoaded = false; streamedText = ""
                engineStatus = "Preparing model..."
                coroutineScope.launch(Dispatchers.IO) {
                    val cachedPath = activity.copyUriToFiles(uri, filename) { msg ->
                        coroutineScope.launch(Dispatchers.Main) { engineStatus = msg }
                    }
                    if (cachedPath == null) {
                        withContext(Dispatchers.Main) { engineStatus = "Error: Could not read model file."; isLoading = false }
                        return@launch
                    }
                    withContext(Dispatchers.Main) { engineStatus = "Loading model..." }

                    // Get engine for format
                    val engine = EngineManager.getEngineForFormat(cachedPath)
                    engine.setConfig(SettingsManager.toConfig())
                    engine.setRepeatPenalty(SettingsManager.toRepeatPenaltyConfig())
                    engine.setSystemPrompt(SettingsManager.systemPrompt)

                    val success = engine.loadModel(cachedPath)
                    if (success) {
                        modelInfo = engine.getModelInfo()
                    }
                    withContext(Dispatchers.Main) {
                        isLoading = false; modelLoaded = success; modelFilename = filename
                        engineStatus = if (success) "${engine.engineName}: $filename" else "Load failed (OOM?)"
                        if (success) selectedScreen = Screen.CHAT
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(BgDeep)) {
        // ── Top Bar ──
        TopBar(
            engineStatus = engineStatus,
            modelLoaded = modelLoaded,
            isInferring = isInferring,
            kvUsage = kvUsage,
            tokensPerSec = tokensPerSec,
            onLoadClick = {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "*/*"))
                }
                filePicker.launch(intent)
            },
            isLoading = isLoading
        )

        // ── Content ──
        Box(modifier = Modifier.weight(1f)) {
            when (selectedScreen) {
                Screen.CHAT -> ChatScreen(
                    chatHistory = chatHistory,
                    listState = listState,
                    streamedText = streamedText,
                    isInferring = isInferring,
                    isLoading = isLoading,
                    modelLoaded = modelLoaded,
                    promptInput = promptInput,
                    onPromptChange = { promptInput = it },
                    onRun = {
                        if (!isInferring && promptInput.isNotBlank()) {
                            val userMsg = promptInput
                            chatHistory.add(ChatMessage(Role.USER, userMsg))
                            currentSessionId?.let { sid ->
                                ChatManager.addMessage(sid, ChatManager.Message("user", userMsg))
                            }
                            promptInput = ""; streamedText = ""; isInferring = true
                            coroutineScope.launch(Dispatchers.IO) {
                                EngineManager.getCurrentEngine()?.executeInference(userMsg)
                            }
                        }
                    },
                    onAbort = { EngineManager.getCurrentEngine()?.abortInference() },
                    onReset = {
                        EngineManager.getCurrentEngine()?.resetContext()
                        chatHistory.clear(); streamedText = ""
                        engineStatus = "Context reset"
                    },
                    onCopyChat = {
                        val text = currentSessionId?.let { ChatManager.exportSession(it) } ?: ""
                        clipManager.setPrimaryClip(ClipData.newPlainText("Chat", text))
                    },
                    clipManager = clipManager
                )
                Screen.MODELS -> ModelsScreen(
                    modelLoaded = modelLoaded,
                    modelFilename = modelFilename,
                    modelInfo = modelInfo,
                    totalTokens = totalTokens,
                    onUnload = {
                        EngineManager.getCurrentEngine()?.unloadModel()
                        modelLoaded = false; modelFilename = ""; modelInfo = null
                        engineStatus = "Model unloaded"
                    }
                )
                Screen.SETTINGS -> SettingsScreen(
                    nCtxStr, { nCtxStr = it },
                    maxTokensStr, { maxTokensStr = it },
                    tempStr, { tempStr = it },
                    topPStr, { topPStr = it },
                    minPStr, { minPStr = it },
                    gpuLayersStr, { gpuLayersStr = it },
                    nThreadsStr, { nThreadsStr = it },
                    repeatPenStr, { repeatPenStr = it },
                    freqPenStr, { freqPenStr = it },
                    presPenStr, { presPenStr = it },
                    systemPrompt, { systemPrompt = it },
                    onApply = {
                        applySettings()
                        engineStatus = if (modelLoaded) "Settings applied" else "Settings saved"
                    },
                    onAutoDetect = {
                        val deviceInfo = EngineManager.getDeviceInfo()
                        if (deviceInfo != null) {
                            SettingsManager.applyToDeviceDefaults(deviceInfo)
                            nCtxStr = SettingsManager.nCtx.toString()
                            gpuLayersStr = SettingsManager.gpuLayers.toString()
                            nThreadsStr = SettingsManager.threads.toString()
                            engineStatus = "Auto-detected: ${deviceInfo.socModel}"
                        }
                    }
                )
                Screen.INFO -> InfoScreen(modelInfo, modelFilename, totalTokens, modelLoaded)
                Screen.BENCH -> BenchScreen(
                    modelLoaded = modelLoaded,
                    benchResult = benchResult,
                    isBenching = isBenching,
                    onRunBench = {
                        if (!isInferring) {
                            isBenching = true
                            coroutineScope.launch(Dispatchers.IO) {
                                val raw = EngineManager.getCurrentEngine()?.benchmark(512, 128)
                                withContext(Dispatchers.Main) {
                                    benchResult = raw?.toString() ?: "{}"; isBenching = false
                                }
                            }
                        }
                    }
                )
            }
        }

        // ── Bottom Navigation ──
        NavigationBar(
            containerColor = BgCard,
            contentColor = AccentCyan
        ) {
            NavigationBarItem(
                icon = { Icon(Icons.Filled.Chat, contentDescription = null) },
                label = { Text("Chat", fontSize = 11.sp) },
                selected = selectedScreen == Screen.CHAT,
                onClick = { selectedScreen = Screen.CHAT },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = AccentCyan,
                    selectedTextColor = AccentCyan,
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted,
                    indicatorColor = BgInput
                )
            )
            NavigationBarItem(
                icon = { Icon(Icons.Filled.SmartToy, contentDescription = null) },
                label = { Text("Models", fontSize = 11.sp) },
                selected = selectedScreen == Screen.MODELS,
                onClick = { selectedScreen = Screen.MODELS },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = AccentCyan,
                    selectedTextColor = AccentCyan,
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted,
                    indicatorColor = BgInput
                )
            )
            NavigationBarItem(
                icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                label = { Text("Settings", fontSize = 11.sp) },
                selected = selectedScreen == Screen.SETTINGS,
                onClick = { selectedScreen = Screen.SETTINGS },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = AccentCyan,
                    selectedTextColor = AccentCyan,
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted,
                    indicatorColor = BgInput
                )
            )
            NavigationBarItem(
                icon = { Icon(Icons.Filled.Info, contentDescription = null) },
                label = { Text("Info", fontSize = 11.sp) },
                selected = selectedScreen == Screen.INFO,
                onClick = { selectedScreen = Screen.INFO },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = AccentCyan,
                    selectedTextColor = AccentCyan,
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted,
                    indicatorColor = BgInput
                )
            )
            NavigationBarItem(
                icon = { Icon(Icons.Filled.Speed, contentDescription = null) },
                label = { Text("Bench", fontSize = 11.sp) },
                selected = selectedScreen == Screen.BENCH,
                onClick = { selectedScreen = Screen.BENCH },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = AccentCyan,
                    selectedTextColor = AccentCyan,
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted,
                    indicatorColor = BgInput
                )
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Top Bar
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun TopBar(
    engineStatus: String, modelLoaded: Boolean, isInferring: Boolean,
    kvUsage: Int, tokensPerSec: Float, onLoadClick: () -> Unit, isLoading: Boolean
) {
    Surface(color = BgCard, shadowElevation = 4.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("GGUF ZeroCopy",
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        color = AccentCyan, fontSize = 16.sp)
                    Text(engineStatus,
                        color = if (modelLoaded) AccentGreen else TextSecond,
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 2.dp))
                }
                Button(
                    onClick = onLoadClick,
                    enabled = !isLoading && !isInferring,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (modelLoaded) Color(0xFF1E3A5F) else AccentCyan)
                ) {
                    Text(
                        when { isLoading -> "Loading..."; modelLoaded -> "Swap Model"; else -> "Load Model" },
                        color = if (modelLoaded) AccentCyan else Color.Black,
                        fontSize = 12.sp
                    )
                }
            }
            if (modelLoaded) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatChip("KV", "$kvUsage%", if (kvUsage > 80) AccentRed else AccentGreen)
                    if (isInferring)
                        StatChip("TPS", "${"%.1f".format(tokensPerSec)}", AccentAmber)
                }
            }
        }
    }
}

@Composable
fun StatChip(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(BgInput)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 10.sp, color = TextSecond, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(4.dp))
        Text(value, fontSize = 10.sp, color = color, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Chat Screen
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun ChatScreen(
    chatHistory: List<ChatMessage>, listState: LazyListState, streamedText: String,
    isInferring: Boolean, isLoading: Boolean, modelLoaded: Boolean,
    promptInput: String, onPromptChange: (String) -> Unit,
    onRun: () -> Unit, onAbort: () -> Unit, onReset: () -> Unit,
    onCopyChat: () -> Unit, clipManager: ClipboardManager
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            if (chatHistory.isEmpty() && !modelLoaded) {
                item { EmptyState() }
            }
            items(chatHistory) { msg ->
                MessageBubble(msg = msg, onCopy = { content ->
                    clipManager.setPrimaryClip(ClipData.newPlainText("Message", content))
                })
            }
            if (isInferring && streamedText.isNotEmpty()) {
                item { StreamingBubble(streamText = streamedText) }
            }
            if (isLoading) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = AccentCyan, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Loading model...", color = TextSecond, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    }
                }
            }
        }
        // Input bar
        Surface(color = BgCard, shadowElevation = 8.dp) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                OutlinedTextField(
                    value = promptInput, onValueChange = onPromptChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(if (modelLoaded) "Type a message..." else "Load a model first", color = TextSecond, fontSize = 13.sp) },
                    enabled = modelLoaded && !isLoading,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentCyan, unfocusedBorderColor = Color(0xFF334155),
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                        disabledTextColor = TextSecond, disabledBorderColor = Color(0xFF1E293B)
                    ),
                    maxLines = 6, shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = if (isInferring) onAbort else onRun,
                        enabled = modelLoaded && !isLoading && (isInferring || promptInput.isNotBlank()),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isInferring) AccentRed else AccentCyan)
                    ) {
                        Text(if (isInferring) "Stop" else "Send", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(onClick = onReset, enabled = modelLoaded && !isInferring,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentAmber)
                    ) { Text("Reset", fontSize = 12.sp) }
                    OutlinedButton(onClick = onCopyChat, enabled = chatHistory.isNotEmpty(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecond)
                    ) { Text("Copy", fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
fun EmptyState() {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.SmartToy, contentDescription = null, modifier = Modifier.size(64.dp), tint = AccentCyan)
        Spacer(Modifier.height(16.dp))
        Text("GGUF ZeroCopy", fontFamily = FontFamily.Monospace, color = AccentCyan, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(8.dp))
        Text("Load a model to start chatting", color = TextSecond, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text("Supports GGUF, MNN, LiteRT-LM", color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun MessageBubble(msg: ChatMessage, onCopy: (String) -> Unit) {
    val isUser = msg.role == Role.USER
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        if (!isUser) {
            Box(modifier = Modifier.size(28.dp).clip(CircleShape).background(AccentCyan), contentAlignment = Alignment.Center) {
                Text("AI", fontSize = 9.sp, color = Color.Black, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(6.dp))
        }
        Column(modifier = Modifier.widthIn(max = 300.dp)) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(
                        topStart = if (isUser) 16.dp else 4.dp,
                        topEnd = if (isUser) 4.dp else 16.dp,
                        bottomStart = 16.dp, bottomEnd = 16.dp
                    ))
                    .background(if (isUser) UserBubble else BotBubble)
                    .clickable { onCopy(msg.content) }
                    .padding(12.dp)
            ) {
                Text(msg.content, color = TextPrimary, fontSize = 14.sp, lineHeight = 20.sp)
            }
            Row(modifier = Modifier.padding(top = 3.dp, start = 4.dp, end = 4.dp),
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
                val ts = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp))
                Text(ts, color = TextMuted, fontSize = 10.sp)
                if (!isUser && msg.tokensPerSec > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text("${"%.1f".format(msg.tokensPerSec)} t/s", color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        if (isUser) {
            Spacer(Modifier.width(6.dp))
            Box(modifier = Modifier.size(28.dp).clip(CircleShape).background(AccentGreen), contentAlignment = Alignment.Center) {
                Text("U", fontSize = 12.sp, color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun StreamingBubble(streamText: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val alpha by infiniteTransition.animateFloat(initialValue = 0f, targetValue = 1f, label = "cursorAlpha",
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(modifier = Modifier.size(28.dp).clip(CircleShape).background(AccentCyan), contentAlignment = Alignment.Center) {
            Text("AI", fontSize = 9.sp, color = Color.Black, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(6.dp))
        Box(modifier = Modifier.widthIn(max = 300.dp)
            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp))
            .background(BotBubble).padding(12.dp)) {
            Text(streamText, color = TextPrimary, fontSize = 14.sp, lineHeight = 21.sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Models Screen
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun ModelsScreen(modelLoaded: Boolean, modelFilename: String, modelInfo: JSONObject?, totalTokens: Int, onUnload: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Models", fontSize = 20.sp, color = AccentCyan, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)

        if (!modelLoaded) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                Text("No model loaded", color = TextSecond)
            }
        } else {
            Card(colors = CardDefaults.cardColors(containerColor = BgCard)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Current Model", fontSize = 14.sp, color = AccentCyan, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(modelFilename, color = TextPrimary, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    modelInfo?.let { info ->
                        val keys = info.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Text(k, fontSize = 11.sp, color = TextSecond, modifier = Modifier.weight(1f))
                                Text(info.optString(k, "—"), fontSize = 11.sp, color = TextPrimary, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Total tokens generated: $totalTokens", fontSize = 11.sp, color = TextMuted)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onUnload, colors = ButtonDefaults.buttonColors(containerColor = AccentRed)) {
                        Text("Unload Model", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Supported formats
        Card(colors = CardDefaults.cardColors(containerColor = BgCard)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Supported Formats", fontSize = 14.sp, color = AccentCyan, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                FormatRow("GGUF", "llama.cpp", "MIT", "Vulkan/OpenCL GPU")
                FormatRow("MNN", "MNN-LLM", "Apache 2.0", "8.6x faster CPU")
                FormatRow("TFLite/LiteRT", "LiteRT-LM", "Apache 2.0", "GPU/NPU")
            }
        }
    }
}

@Composable
fun FormatRow(format: String, engine: String, license: String, note: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(format, fontSize = 12.sp, color = AccentCyan, fontWeight = FontWeight.Bold, modifier = Modifier.width(80.dp))
        Text(engine, fontSize = 12.sp, color = TextPrimary, modifier = Modifier.width(80.dp))
        Text(license, fontSize = 11.sp, color = TextMuted, modifier = Modifier.width(90.dp))
        Text(note, fontSize = 11.sp, color = TextSecond)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Settings Screen
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun SettingsScreen(
    nCtxStr: String, onNCtxChange: (String) -> Unit,
    maxTokensStr: String, onMaxTokensChange: (String) -> Unit,
    tempStr: String, onTempChange: (String) -> Unit,
    topPStr: String, onTopPChange: (String) -> Unit,
    minPStr: String, onMinPChange: (String) -> Unit,
    gpuLayersStr: String, onGpuLayersChange: (String) -> Unit,
    nThreadsStr: String, onNThreadsChange: (String) -> Unit,
    repeatPenStr: String, onRepeatPenChange: (String) -> Unit,
    freqPenStr: String, onFreqPenChange: (String) -> Unit,
    presPenStr: String, onPresPenChange: (String) -> Unit,
    systemPrompt: String, onSystemPromptChange: (String) -> Unit,
    onApply: () -> Unit, onAutoDetect: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", fontSize = 20.sp, color = AccentCyan, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)

        // Auto-detect button
        Button(onClick = onAutoDetect, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = AccentPurple)) {
            Text("Auto-Detect Device", color = Color.Black, fontWeight = FontWeight.Bold)
        }

        SettingsSection("Context & Generation") {
            SettingRow("Context Window", "512-32768. 8192 safe for 6-8GB RAM.", nCtxStr, onNCtxChange)
            SettingRow("Max New Tokens", "64-8192 per turn.", maxTokensStr, onMaxTokensChange)
            SettingRow("GPU Layers", "99=GPU, 0=CPU. Auto-detected.", gpuLayersStr, onGpuLayersChange)
            SettingRow("CPU Threads", "1-16. Auto-detected.", nThreadsStr, onNThreadsChange)
        }
        SettingsSection("Sampling") {
            SettingRow("Temperature", "0=deterministic, 1.0=creative.", tempStr, onTempChange)
            SettingRow("Top-P", "0-1 nucleus sampling.", topPStr, onTopPChange)
            SettingRow("Min-P", "0-1 low-prob filter.", minPStr, onMinPChange)
        }
        SettingsSection("Repetition") {
            SettingRow("Repeat Penalty", "1.0=off.", repeatPenStr, onRepeatPenChange)
            SettingRow("Frequency Penalty", "0.0=off.", freqPenStr, onFreqPenChange)
            SettingRow("Presence Penalty", "0.0=off.", presPenStr, onPresPenChange)
        }
        SettingsSection("System Prompt") {
            OutlinedTextField(value = systemPrompt, onValueChange = onSystemPromptChange,
                modifier = Modifier.fillMaxWidth(), maxLines = 6,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentCyan, unfocusedBorderColor = Color(0xFF334155),
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace))
        }

        Button(onClick = onApply, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)) {
            Text("Apply Settings", color = Color.Black, fontWeight = FontWeight.Bold)
        }
        Text("Note: Context and GPU layers require model reload.", fontSize = 11.sp, color = AccentAmber, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = BgCard), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontSize = 13.sp, color = AccentCyan, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
            content()
        }
    }
}

@Composable
fun SettingRow(label: String, hint: String, value: String, onChange: (String) -> Unit) {
    Column {
        Text(label, fontSize = 12.sp, color = TextSecond, fontWeight = FontWeight.Medium)
        Text(hint, fontSize = 10.sp, color = TextMuted)
        OutlinedTextField(value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentCyan, unfocusedBorderColor = Color(0xFF334155),
                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Info Screen
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun InfoScreen(modelInfo: JSONObject?, modelFilename: String, totalTokens: Int, modelLoaded: Boolean) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Info", fontSize = 20.sp, color = AccentCyan, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)

        if (!modelLoaded) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                Text("Load a model to see info", color = TextSecond)
            }
            return@Column
        }

        Card(colors = CardDefaults.cardColors(containerColor = BgCard)) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("Architecture", fontSize = 13.sp, color = AccentCyan, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(8.dp))
                Text("ASharedMemory ring buffer\nwrite_pos(4) | flags(4) | tokens_gen(4) | reserved(4) | data(512KB)\n\nKotlin polls shared memory every 80ms.\nC++ writes tokens as UTF-8 into data region.",
                    fontSize = 11.sp, color = TextSecond, fontFamily = FontFamily.Monospace, lineHeight = 17.sp)
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = BgCard)) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("Performance", fontSize = 13.sp, color = AccentCyan, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(8.dp))
                Text("- Big core pinning (sched_setaffinity)\n- Process priority boost (-20)\n- RAM locking (mlockall)\n- ThinLTO + armv8.4a+dotprod+crc\n- OpenCL for Adreno / Vulkan for Mali",
                    fontSize = 11.sp, color = TextSecond, fontFamily = FontFamily.Monospace, lineHeight = 17.sp)
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = BgCard)) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("Licenses (Open Source)", fontSize = 13.sp, color = AccentCyan, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(8.dp))
                Text(LicenseNotices.getShortNotices(), fontSize = 11.sp, color = TextSecond, fontFamily = FontFamily.Monospace, lineHeight = 17.sp)
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = BgCard)) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("Session Stats", fontSize = 13.sp, color = AccentCyan, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(8.dp))
                Text("Total Tokens Generated: $totalTokens", fontSize = 12.sp, color = TextPrimary)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Bench Screen
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun BenchScreen(modelLoaded: Boolean, benchResult: String, isBenching: Boolean, onRunBench: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(16.dp))
        Text("Benchmark", color = AccentCyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text("Measures prompt-processing (PP) and token-generation (TG) speed.", color = TextSecond, fontSize = 12.sp, textAlign = TextAlign.Center)

        Button(onClick = onRunBench, enabled = modelLoaded && !isBenching, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AccentAmber)) {
            if (isBenching) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (isBenching) "Benchmarking..." else "Run Benchmark (PP=512, TG=128)", color = Color.Black, fontWeight = FontWeight.Bold)
        }

        if (!modelLoaded) Text("Load a model first.", color = AccentRed, fontSize = 13.sp)

        var ppTps = 0.0; var tgTps = 0.0; var parseError = false
        try {
            if (benchResult.isNotEmpty()) {
                val obj = JSONObject(benchResult)
                ppTps = obj.optDouble("pp_tps", 0.0)
                tgTps = obj.optDouble("tg_tps", 0.0)
            }
        } catch (e: Exception) { parseError = true }

        if (benchResult.isNotEmpty() && !parseError && (ppTps > 0 || tgTps > 0)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BenchCard("Prompt Processing", "%.1f".format(ppTps), "tokens/sec")
                BenchCard("Token Generation", "%.1f".format(tgTps), "tokens/sec")
            }
        } else if (benchResult.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = BgCard)) {
                Text(benchResult, modifier = Modifier.padding(12.dp), color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun BenchCard(label: String, value: String, unit: String) {
    Card(colors = CardDefaults.cardColors(containerColor = BgCard), shape = RoundedCornerShape(16.dp), modifier = Modifier.width(150.dp)) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = AccentCyan, fontFamily = FontFamily.Monospace)
            Text(unit, fontSize = 11.sp, color = TextSecond)
            Spacer(Modifier.height(4.dp))
            Text(label, fontSize = 12.sp, color = TextPrimary, textAlign = TextAlign.Center)
        }
    }
}
