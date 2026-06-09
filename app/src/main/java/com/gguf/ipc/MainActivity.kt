package com.gguf.ipc

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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

// ─── Design tokens ────────────────────────────────────────────────────────────
private val BgDeep      = Color(0xFF060A11)
private val BgCard      = Color(0xFF0D1520)
private val BgInput     = Color(0xFF0B1221)
private val AccentCyan  = Color(0xFF22D3EE)
private val AccentGreen = Color(0xFF34D399)
private val AccentAmber = Color(0xFFFBBF24)
private val AccentRed   = Color(0xFFF87171)
private val AccentPurple= Color(0xFFA78BFA)
private val TextPrimary = Color(0xFFEEF2FF)
private val TextSecond  = Color(0xFF8B9DB5)
private val BotBubble   = Color(0xFF0F2416)
private val UserBubble  = Color(0xFF0D1E3A)
private val BorderColor = Color(0xFF1E2D40)

// ─── Data classes ─────────────────────────────────────────────────────────────
enum class Role { USER, ASSISTANT }
data class ChatMessage(
    val role: Role,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val tokensPerSec: Float = 0f,
    val tokenCount: Int = 0
)

// ─── Activity ─────────────────────────────────────────────────────────────────
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EngineCore.bootZeroCopyEngine()
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
                    GgufEngineScreen()
                }
            }
        }
    }

    fun copyUriToFiles(uri: Uri, filename: String, onProgress: (String) -> Unit): String? = try {
        val dest = File(filesDir, filename)
        contentResolver.openInputStream(uri)?.use { input: InputStream ->
            onProgress("Copying model…")
            dest.outputStream().use { input.copyTo(it, bufferSize = 8 * 1024 * 1024) }
        }
        dest.absolutePath
    } catch (e: Exception) { null }
}

// ─── Main screen ──────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GgufEngineScreen() {
    val activity       = LocalContext.current as MainActivity
    val coroutineScope = rememberCoroutineScope()
    val listState      = rememberLazyListState()
    val clipManager    = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    // Model state
    var engineStatus   by remember { mutableStateOf("No model loaded") }
    var isLoading      by remember { mutableStateOf(false) }
    var isInferring    by remember { mutableStateOf(false) }
    var modelLoaded    by remember { mutableStateOf(false) }
    var modelFilename  by remember { mutableStateOf("") }
    var modelInfo      by remember { mutableStateOf<JSONObject?>(null) }
    var streamedText   by remember { mutableStateOf("") }
    var promptInput    by remember { mutableStateOf("") }
    var kvUsage        by remember { mutableStateOf(0) }
    var tokensPerSec   by remember { mutableStateOf(0f) }
    var inferStartMs   by remember { mutableStateOf(0L) }
    var totalTokens    by remember { mutableStateOf(0) }

    // Track the path + settings the model was loaded with for auto-reload
    var currentModelPath   by remember { mutableStateOf("") }
    var loadedNCtx         by remember { mutableStateOf(0) }
    var loadedGpuLayers    by remember { mutableStateOf(-1) }

    val chatHistory = remember { mutableStateListOf<ChatMessage>() }
    var selectedTab by remember { mutableStateOf(0) }

    // Auto-detect hardware on first launch
    val detectedGpuLayers = remember { EngineCore.autoDetectGpuLayers(activity) }
    val detectedThreads   = remember { EngineCore.autoDetectThreads() }
    val gpuTierLabel      = remember { EngineCore.getGpuTierLabel(activity) }

    // Settings — initialised from hardware detection
    var nCtxStr      by remember { mutableStateOf("8192") }
    var maxTokensStr by remember { mutableStateOf("4096") }
    var tempStr      by remember { mutableStateOf("0.7") }
    var topPStr      by remember { mutableStateOf("0.9") }
    var minPStr      by remember { mutableStateOf("0.05") }
    var gpuLayersStr by remember { mutableStateOf(detectedGpuLayers.toString()) }
    var nThreadsStr  by remember { mutableStateOf(detectedThreads.toString()) }
    var repeatPenStr by remember { mutableStateOf("1.1") }
    var freqPenStr   by remember { mutableStateOf("0.0") }
    var presPenStr   by remember { mutableStateOf("0.0") }
    var systemPrompt by remember { mutableStateOf("You are a helpful, concise assistant running on-device. Respond clearly and directly.") }

    var benchResult by remember { mutableStateOf("") }
    var isBenching  by remember { mutableStateOf(false) }

    // ── Core loader ─────────────────────────────────────────────────────────
    fun doLoad(cachedPath: String) {
        isLoading = true; modelLoaded = false; streamedText = ""
        engineStatus = "Loading into GGML backend…"

        val cfg = EngineCore.Config(
            nCtx         = nCtxStr.toIntOrNull()?.coerceIn(512, 32768) ?: 8192,
            maxNewTokens = maxTokensStr.toIntOrNull()?.coerceIn(64, 8192) ?: 4096,
            temperature  = tempStr.toFloatOrNull()?.coerceIn(0f, 2f) ?: 0.7f,
            topP         = topPStr.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.9f,
            minP         = minPStr.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.05f,
            nGpuLayers   = gpuLayersStr.toIntOrNull()?.coerceIn(0, 999) ?: detectedGpuLayers,
            nThreads     = nThreadsStr.toIntOrNull()?.coerceIn(1, 16) ?: detectedThreads,
            seed         = -1
        )
        EngineCore.setEngineConfig(cfg)
        EngineCore.setSystemPromptNative(systemPrompt)
        EngineCore.setRepeatPenalty(EngineCore.RepeatPenaltyConfig(
            repeatPenStr.toFloatOrNull() ?: 1.1f,
            freqPenStr.toFloatOrNull()   ?: 0.0f,
            presPenStr.toFloatOrNull()   ?: 0.0f
        ))

        coroutineScope.launch(Dispatchers.IO) {
            val success = EngineCore.loadModel(cachedPath)
            if (success) {
                try { modelInfo = JSONObject(EngineCore.getModelInfoNative()) } catch (_: Exception) {}
            }
            withContext(Dispatchers.Main) {
                isLoading = false; modelLoaded = success
                if (success) {
                    currentModelPath = cachedPath
                    loadedNCtx       = cfg.nCtx
                    loadedGpuLayers  = cfg.nGpuLayers
                    chatHistory.clear(); streamedText = ""
                    engineStatus = "✓ ${File(cachedPath).name}"
                    selectedTab  = 0
                } else {
                    engineStatus = "✗ Load failed (OOM or corrupt file)"
                }
            }
        }
    }

    fun applySettings() {
        val newCtx = nCtxStr.toIntOrNull()?.coerceIn(512, 32768) ?: 8192
        val newGpu = gpuLayersStr.toIntOrNull()?.coerceIn(0, 999) ?: detectedGpuLayers

        // Always push non-reload params immediately
        val cfg = EngineCore.Config(
            nCtx         = newCtx,
            maxNewTokens = maxTokensStr.toIntOrNull()?.coerceIn(64, 8192) ?: 4096,
            temperature  = tempStr.toFloatOrNull()?.coerceIn(0f, 2f) ?: 0.7f,
            topP         = topPStr.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.9f,
            minP         = minPStr.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.05f,
            nGpuLayers   = newGpu,
            nThreads     = nThreadsStr.toIntOrNull()?.coerceIn(1, 16) ?: detectedThreads,
            seed         = -1
        )
        EngineCore.setEngineConfig(cfg)
        EngineCore.setSystemPromptNative(systemPrompt)
        EngineCore.setRepeatPenalty(EngineCore.RepeatPenaltyConfig(
            repeatPenStr.toFloatOrNull() ?: 1.1f,
            freqPenStr.toFloatOrNull()   ?: 0.0f,
            presPenStr.toFloatOrNull()   ?: 0.0f
        ))

        // AUTO-RELOAD if nCtx or GPU layers changed and model is loaded
        if (modelLoaded && currentModelPath.isNotEmpty() &&
            (newCtx != loadedNCtx || newGpu != loadedGpuLayers))
        {
            engineStatus = "↻ Reloading model with new settings…"
            doLoad(currentModelPath)
        } else {
            engineStatus = if (modelLoaded) "Settings applied ✓" else "Settings saved (load model first)"
        }
    }

    // ── Stream polling ───────────────────────────────────────────────────────
    LaunchedEffect(isInferring) {
        if (isInferring) {
            inferStartMs = System.currentTimeMillis()
            while (isInferring) {
                delay(80)
                val partial = EngineCore.readPartialStream()
                if (partial.isNotEmpty()) streamedText = partial
                val elapsed = (System.currentTimeMillis() - inferStartMs) / 1000f
                val toks    = EngineCore.getTokensGenerated()
                if (elapsed > 0) tokensPerSec = toks / elapsed
                kvUsage = EngineCore.getKvCacheUsageNative()

                if (EngineCore.isInferenceDone()) {
                    delay(20)
                    val finalText = EngineCore.readTokenStream()
                    val finalToks = EngineCore.getTokensGenerated()
                    val finalTps  = if (elapsed > 0) finalToks / elapsed else 0f
                    totalTokens  += finalToks
                    if (finalText.isNotEmpty()) {
                        chatHistory.add(ChatMessage(Role.ASSISTANT, finalText,
                            tokensPerSec = finalTps, tokenCount = finalToks))
                    }
                    streamedText = ""; isInferring = false
                }
            }
        }
    }

    LaunchedEffect(chatHistory.size, isInferring) {
        if (chatHistory.isNotEmpty()) listState.animateScrollToItem(chatHistory.size - 1)
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val filename = uri.lastPathSegment
                    ?.substringAfterLast('/')?.substringAfterLast(':') ?: "model.gguf"
                engineStatus = "Preparing model…"
                coroutineScope.launch(Dispatchers.IO) {
                    val path = activity.copyUriToFiles(uri, filename) { msg ->
                        coroutineScope.launch(Dispatchers.Main) { engineStatus = msg }
                    }
                    if (path == null) {
                        withContext(Dispatchers.Main) {
                            engineStatus = "Error: could not read file"; isLoading = false
                        }
                        return@launch
                    }
                    withContext(Dispatchers.Main) { doLoad(path) }
                }
            }
        }
    }

    // ── Layout ───────────────────────────────────────────────────────────────
    Column(modifier = Modifier.fillMaxSize().background(BgDeep)) {
        TopBar(
            engineStatus = engineStatus, modelLoaded = modelLoaded,
            isInferring  = isInferring, kvUsage = kvUsage, tokensPerSec = tokensPerSec,
            isLoading    = isLoading,
            onLoadClick  = {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "*/*"))
                }
                filePicker.launch(intent)
            }
        )

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor   = BgCard,
            contentColor     = AccentCyan
        ) {
            listOf("💬 Chat", "⚙ Settings", "ℹ Info", "⚡ Bench").forEachIndexed { i, label ->
                Tab(
                    selected = selectedTab == i, onClick = { selectedTab = i },
                    text = {
                        Text(label, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            color = if (selectedTab == i) AccentCyan else TextSecond)
                    }
                )
            }
        }

        when (selectedTab) {
            0 -> ChatTab(
                chatHistory    = chatHistory, listState = listState,
                streamedText   = streamedText, isInferring = isInferring,
                isLoading      = isLoading, modelLoaded = modelLoaded,
                promptInput    = promptInput, onPromptChange = { promptInput = it },
                onRun = {
                    if (!isInferring && promptInput.isNotBlank()) {
                        chatHistory.add(ChatMessage(Role.USER, promptInput))
                        val msg = promptInput
                        promptInput = ""; streamedText = ""; isInferring = true
                        coroutineScope.launch(Dispatchers.IO) {
                            EngineCore.executeZeroCopyInference(msg)
                        }
                    }
                },
                onAbort  = { EngineCore.abortInferenceNative() },
                onReset  = {
                    EngineCore.resetContextNative(); chatHistory.clear(); streamedText = ""
                    engineStatus = "Context cleared ✓"
                },
                onCopyChat  = {
                    val text = EngineCore.exportChatHistoryNative()
                    clipManager.setPrimaryClip(ClipData.newPlainText("Chat", text))
                },
                clipManager = clipManager
            )
            1 -> SettingsTab(
                nCtxStr, { nCtxStr = it }, maxTokensStr, { maxTokensStr = it },
                tempStr, { tempStr = it }, topPStr, { topPStr = it },
                minPStr, { minPStr = it }, gpuLayersStr, { gpuLayersStr = it },
                nThreadsStr, { nThreadsStr = it }, repeatPenStr, { repeatPenStr = it },
                freqPenStr, { freqPenStr = it }, presPenStr, { presPenStr = it },
                systemPrompt, { systemPrompt = it },
                gpuTierLabel = gpuTierLabel,
                detectedGpuLayers = detectedGpuLayers,
                onApply = { applySettings() }
            )
            2 -> InfoTab(modelInfo, modelFilename.ifEmpty { File(currentModelPath).name }, totalTokens, modelLoaded)
            3 -> BenchmarkTab(
                modelLoaded = modelLoaded, benchResult = benchResult, isBenching = isBenching,
                onRunBench = {
                    if (!isInferring) {
                        isBenching = true
                        coroutineScope.launch(Dispatchers.IO) {
                            val raw = EngineCore.benchmarkNative(512, 128)
                            withContext(Dispatchers.Main) { benchResult = raw; isBenching = false }
                        }
                    }
                }
            )
        }
    }
}

// ─── Top Bar ──────────────────────────────────────────────────────────────────
@Composable
fun TopBar(
    engineStatus: String, modelLoaded: Boolean, isInferring: Boolean,
    kvUsage: Int, tokensPerSec: Float, onLoadClick: () -> Unit, isLoading: Boolean
) {
    Surface(
        color = BgCard,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Logo + status
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp).clip(RoundedCornerShape(8.dp))
                                .background(Brush.radialGradient(listOf(AccentCyan, Color(0xFF0E7490)))),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("G", color = Color.Black, fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace, fontSize = 16.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("GGUF ZeroCopy v5",
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                color = TextPrimary, fontSize = 15.sp)
                            Text(engineStatus,
                                color = when {
                                    isLoading    -> AccentAmber
                                    modelLoaded  -> AccentGreen
                                    else         -> TextSecond
                                },
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(top = 1.dp))
                        }
                    }
                }
                Button(
                    onClick = onLoadClick,
                    enabled = !isLoading && !isInferring,
                    shape   = RoundedCornerShape(10.dp),
                    colors  = ButtonDefaults.buttonColors(
                        containerColor = if (modelLoaded) Color(0xFF1A3050) else AccentCyan,
                        disabledContainerColor = Color(0xFF1A2535)
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        when { isLoading -> "⏳ Loading…"; modelLoaded -> "⟳ Swap"; else -> "📂 Load GGUF" },
                        color = if (modelLoaded && !isLoading) AccentCyan else Color.Black,
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                    )
                }
            }
            if (modelLoaded) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatChip("KV", "$kvUsage%",
                        when { kvUsage > 90 -> AccentRed; kvUsage > 70 -> AccentAmber; else -> AccentGreen })
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
            .background(BgDeep)
            .border(1.dp, BorderColor, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 9.sp, color = TextSecond, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(5.dp))
        Text(value, fontSize = 10.sp, color = color,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

// ─── Chat Tab ─────────────────────────────────────────────────────────────────
@Composable
fun ChatTab(
    chatHistory: List<ChatMessage>, listState: LazyListState, streamedText: String,
    isInferring: Boolean, isLoading: Boolean, modelLoaded: Boolean,
    promptInput: String, onPromptChange: (String) -> Unit,
    onRun: () -> Unit, onAbort: () -> Unit, onReset: () -> Unit,
    onCopyChat: () -> Unit, clipManager: ClipboardManager
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state    = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 14.dp)
        ) {
            if (chatHistory.isEmpty() && !modelLoaded && !isLoading) {
                item { EmptyState() }
            }
            if (chatHistory.isEmpty() && modelLoaded) {
                item { ModelReadyState() }
            }
            items(chatHistory) { msg ->
                MessageBubble(msg, onCopy = { content ->
                    clipManager.setPrimaryClip(ClipData.newPlainText("Message", content))
                })
            }
            if (isInferring && streamedText.isNotEmpty()) {
                item { StreamingBubble(streamedText) }
            }
            if (isLoading) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp),
                            color = AccentCyan, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Loading model…", color = TextSecond,
                            fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    }
                }
            }
        }

        // Input bar
        Surface(
            color = BgCard, shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                OutlinedTextField(
                    value = promptInput, onValueChange = onPromptChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(if (modelLoaded) "Type a message…" else "Load a model first",
                            color = TextSecond, fontSize = 13.sp)
                    },
                    enabled = modelLoaded && !isLoading,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = AccentCyan,
                        unfocusedBorderColor = BorderColor,
                        focusedTextColor     = TextPrimary,
                        unfocusedTextColor   = TextPrimary,
                        disabledBorderColor  = Color(0xFF1A2535)
                    ),
                    maxLines = 5,
                    shape = RoundedCornerShape(14.dp)
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick  = if (isInferring) onAbort else onRun,
                        enabled  = modelLoaded && !isLoading && (isInferring || promptInput.isNotBlank()),
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape    = RoundedCornerShape(12.dp),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor = if (isInferring) AccentRed else AccentCyan)
                    ) {
                        Text(if (isInferring) "■ Stop" else "▶ Send",
                            color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    OutlinedButton(
                        onClick = onReset, enabled = modelLoaded && !isInferring,
                        shape   = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(44.dp),
                        colors  = ButtonDefaults.outlinedButtonColors(contentColor = AccentAmber),
                        border  = BorderStroke(1.dp, AccentAmber.copy(alpha = 0.4f))
                    ) { Text("↺", fontSize = 16.sp) }
                    OutlinedButton(
                        onClick = onCopyChat, enabled = chatHistory.isNotEmpty(),
                        shape   = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(44.dp),
                        colors  = ButtonDefaults.outlinedButtonColors(contentColor = TextSecond),
                        border  = BorderStroke(1.dp, BorderColor)
                    ) { Text("⎘", fontSize = 16.sp) }
                }
            }
        }
    }
}

@Composable
fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 60.dp, bottom = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(80.dp).clip(CircleShape)
                .background(Brush.radialGradient(
                    listOf(Color(0xFF0E3044), BgDeep))),
            contentAlignment = Alignment.Center
        ) { Text("🤖", fontSize = 36.sp) }
        Spacer(Modifier.height(20.dp))
        Text("GGUF ZeroCopy v5",
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            color = AccentCyan, fontSize = 20.sp)
        Spacer(Modifier.height(8.dp))
        Text("Load a .gguf model to start chatting",
            color = TextSecond, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Text("Zero-copy IPC · Vulkan Ready · Flash Attention",
            color = Color(0xFF334155), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun ModelReadyState() {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
        contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(12.dp), color = Color(0xFF0A1F12),
            border = BorderStroke(1.dp, AccentGreen.copy(alpha = 0.3f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("✓", color = AccentGreen, fontSize = 14.sp)
                Spacer(Modifier.width(8.dp))
                Text("Model ready — send a message",
                    color = AccentGreen.copy(alpha = 0.8f),
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun MessageBubble(msg: ChatMessage, onCopy: (String) -> Unit) {
    val isUser = msg.role == Role.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            Box(
                modifier = Modifier.size(30.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(AccentCyan, Color(0xFF0891B2)))),
                contentAlignment = Alignment.Center
            ) { Text("AI", fontSize = 9.sp, color = Color.Black, fontWeight = FontWeight.Black) }
            Spacer(Modifier.width(8.dp))
        }

        Column(modifier = Modifier.widthIn(max = 310.dp)) {
            Surface(
                shape = RoundedCornerShape(
                    topStart    = if (isUser) 18.dp else 4.dp,
                    topEnd      = if (isUser) 4.dp else 18.dp,
                    bottomStart = 18.dp, bottomEnd = 18.dp
                ),
                color = if (isUser) UserBubble else BotBubble,
                border = BorderStroke(
                    1.dp, if (isUser) Color(0xFF1A3A60) else Color(0xFF143020)
                ),
                modifier = Modifier.clickable { onCopy(msg.content) }
            ) {
                Box(modifier = Modifier.padding(12.dp)) {
                    if (!isUser) MessageContent(msg.content)
                    else Text(msg.content, color = TextPrimary, fontSize = 14.sp, lineHeight = 21.sp)
                }
            }
            Row(
                modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp),
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
            ) {
                val ts = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp))
                Text(ts, color = Color(0xFF3A4A5C), fontSize = 10.sp)
                if (!isUser && msg.tokensPerSec > 0f) {
                    Spacer(Modifier.width(6.dp))
                    Text("${"%.1f".format(msg.tokensPerSec)} t/s · ${msg.tokenCount} tok",
                        color = Color(0xFF3A4A5C), fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }
        }

        if (isUser) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier.size(30.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(AccentGreen, Color(0xFF059669)))),
                contentAlignment = Alignment.Center
            ) { Text("U", fontSize = 12.sp, color = Color.Black, fontWeight = FontWeight.Black) }
        }
    }
}

@Composable
fun MessageContent(text: String) {
    val hasThink    = text.contains("<think>")
    val thinkClosed = text.contains("</think>")
    val thought = if (hasThink)
        text.substringAfter("<think>").let { if (thinkClosed) it.substringBefore("</think>") else it }
    else ""
    val response = when {
        hasThink && thinkClosed -> text.substringAfter("</think>").trimStart()
        hasThink                -> ""
        else                    -> text
    }

    Column {
        if (thought.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            Surface(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .clickable { expanded = !expanded },
                color = Color(0xFF0A1525),
                border = BorderStroke(1.dp, Color(0xFF1A2C45))
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🧠", fontSize = 12.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(if (expanded) "Hide reasoning" else "Show reasoning",
                            fontSize = 11.sp, color = AccentPurple,
                            fontFamily = FontFamily.Monospace)
                        if (!thinkClosed) {
                            Spacer(Modifier.width(8.dp))
                            CircularProgressIndicator(modifier = Modifier.size(9.dp),
                                color = AccentCyan, strokeWidth = 1.dp)
                        }
                    }
                    AnimatedVisibility(visible = expanded) {
                        Text(thought, fontSize = 12.sp, color = Color(0xFF5A7AAA),
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            if (response.isNotEmpty()) Spacer(Modifier.height(6.dp))
        }
        if (response.isNotEmpty()) {
            Text(response, color = Color(0xFFCCFFD8), fontSize = 14.sp, lineHeight = 22.sp)
        }
    }
}

@Composable
fun StreamingBubble(streamText: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f, label = "alpha",
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse)
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            modifier = Modifier.size(30.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(AccentCyan, Color(0xFF0891B2)))),
            contentAlignment = Alignment.Center
        ) { Text("AI", fontSize = 9.sp, color = Color.Black, fontWeight = FontWeight.Black) }
        Spacer(Modifier.width(8.dp))
        Surface(
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp),
            color = BotBubble,
            border = BorderStroke(1.dp, Color(0xFF143020))
        ) {
            Box(modifier = Modifier.padding(12.dp).widthIn(max = 310.dp)) {
                MessageContent(text = streamText)
            }
        }
    }
}

// ─── Settings Tab ─────────────────────────────────────────────────────────────
@Composable
fun SettingsTab(
    nCtxStr: String,       onNCtxChange: (String) -> Unit,
    maxTokensStr: String,  onMaxTokensChange: (String) -> Unit,
    tempStr: String,       onTempChange: (String) -> Unit,
    topPStr: String,       onTopPChange: (String) -> Unit,
    minPStr: String,       onMinPChange: (String) -> Unit,
    gpuLayersStr: String,  onGpuLayersChange: (String) -> Unit,
    nThreadsStr: String,   onNThreadsChange: (String) -> Unit,
    repeatPenStr: String,  onRepeatPenChange: (String) -> Unit,
    freqPenStr: String,    onFreqPenChange: (String) -> Unit,
    presPenStr: String,    onPresPenChange: (String) -> Unit,
    systemPrompt: String,  onSystemPromptChange: (String) -> Unit,
    gpuTierLabel: String,
    detectedGpuLayers: Int,
    onApply: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // GPU detection badge
        Surface(
            shape = RoundedCornerShape(10.dp), color = Color(0xFF0A1520),
            border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🔍", fontSize = 18.sp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Device: $gpuTierLabel",
                        color = AccentCyan, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                    Text("Auto-detected GPU layers: $detectedGpuLayers",
                        color = TextSecond, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }
        }

        SettingsSection("Context & Compute") {
            SettingRow("Context Window (n_ctx)", "512–32768. Reload required to apply.", nCtxStr, onNCtxChange)
            SettingRow("Max New Tokens",         "Tokens per response (64–8192).",       maxTokensStr, onMaxTokensChange)
            SettingRow("GPU Layers",             "Layers on GPU. Reload required. 0 = CPU-only.", gpuLayersStr, onGpuLayersChange)
            SettingRow("CPU Threads",             "1–16. Unused if fully on GPU.",        nThreadsStr, onNThreadsChange)
        }
        SettingsSection("Sampling") {
            SettingRow("Temperature",  "0 = deterministic · 1.0 = creative",   tempStr, onTempChange)
            SettingRow("Top-P",        "Nucleus sampling (0–1). 0.9 rec.",      topPStr, onTopPChange)
            SettingRow("Min-P",        "Low-prob token filter. 0.05 rec.",      minPStr, onMinPChange)
        }
        SettingsSection("Repetition Penalties") {
            SettingRow("Repeat Penalty",    "1.0 = off · 1.1 recommended.",     repeatPenStr, onRepeatPenChange)
            SettingRow("Frequency Penalty", "Penalise frequent tokens. 0 = off.",freqPenStr,   onFreqPenChange)
            SettingRow("Presence Penalty",  "Penalise seen tokens. 0 = off.",    presPenStr,   onPresPenChange)
        }
        SettingsSection("System Prompt") {
            OutlinedTextField(
                value = systemPrompt, onValueChange = onSystemPromptChange,
                modifier = Modifier.fillMaxWidth(), maxLines = 6,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = AccentCyan, unfocusedBorderColor = BorderColor,
                    focusedTextColor     = TextPrimary, unfocusedTextColor   = TextSecond
                ),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            )
        }

        // Presets
        Text("Quick Presets", fontSize = 12.sp, color = TextSecond,
            fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            listOf(
                "Qwen3"     to Triple("8192",  "0.6", "You are a helpful assistant."),
                "Gemma 4"   to Triple("8192",  "0.7", "You are a helpful assistant."),
                "Reasoning" to Triple("16384", "0.6", "Think step-by-step before answering."),
                "Creative"  to Triple("8192",  "1.0", "You are a creative storyteller.")
            ).forEach { (label, v) ->
                OutlinedButton(
                    onClick = {
                        onNCtxChange(v.first); onTempChange(v.second)
                        onSystemPromptChange(v.third)
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    shape  = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, BorderColor),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan)
                ) { Text(label, fontSize = 11.sp) }
            }
        }

        Button(
            onClick  = onApply,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape    = RoundedCornerShape(12.dp),
            colors   = ButtonDefaults.buttonColors(containerColor = AccentCyan)
        ) {
            Text("Apply Settings", color = Color.Black,
                fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }

        Surface(
            shape = RoundedCornerShape(8.dp), color = Color(0xFF1A1200),
            border = BorderStroke(1.dp, AccentAmber.copy(alpha = 0.3f))
        ) {
            Text(
                "⚡ n_ctx and GPU layers require a model reload — Apply will reload automatically.",
                fontSize = 11.sp, color = AccentAmber, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(10.dp), lineHeight = 16.sp
            )
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = BgCard, shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, BorderColor)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontSize = 12.sp, color = AccentCyan,
                fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
            content()
        }
    }
}

@Composable
fun SettingRow(label: String, hint: String, value: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
        Text(hint,  fontSize = 10.sp, color = TextSecond)
        OutlinedTextField(
            value = value, onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            shape  = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = AccentCyan, unfocusedBorderColor = BorderColor,
                focusedTextColor     = TextPrimary, unfocusedTextColor   = TextPrimary
            ),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        )
    }
}

// ─── Info Tab ─────────────────────────────────────────────────────────────────
@Composable
fun InfoTab(modelInfo: JSONObject?, modelFilename: String, totalTokens: Int, modelLoaded: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(14.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!modelLoaded) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                contentAlignment = Alignment.Center) {
                Text("Load a model to see info", color = TextSecond, fontSize = 13.sp)
            }
            return@Column
        }

        InfoCard("Model File", listOf("Name" to modelFilename))

        if (modelInfo != null) {
            val pairs = mutableListOf<Pair<String, String>>()
            val keys  = modelInfo.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                pairs.add(k to modelInfo.optString(k, "—"))
            }
            InfoCard("Model Metadata", pairs)
        }

        InfoCard("Session", listOf("Total Tokens Generated" to totalTokens.toString()))

        Surface(color = BgCard, shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, BorderColor)) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("IPC Architecture", fontSize = 12.sp, color = AccentCyan,
                    fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(8.dp))
                Text("""
ASharedMemory ring buffer (512 KB + 16 B header)
  [write_pos·4][flags·4][tokens_gen·4][tps·4][data·512K]

Kotlin polls isInferenceDoneNative() every 80 ms.
C++ writes UTF-8 tokens directly into data region.
Zero-copy: Kotlin reads via read-only ByteBuffer map.
KV cache: Q8_0 quantised (saves ~50% VRAM).
                """.trimIndent(),
                    fontSize = 11.sp, color = TextSecond,
                    fontFamily = FontFamily.Monospace, lineHeight = 18.sp)
            }
        }
    }
}

@Composable
fun InfoCard(title: String, pairs: List<Pair<String, String>>) {
    Surface(color = BgCard, shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, BorderColor)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, fontSize = 12.sp, color = AccentCyan,
                fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(8.dp))
            pairs.forEach { (k, v) ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(k, fontSize = 11.sp, color = TextSecond, modifier = Modifier.weight(1f))
                    Text(v, fontSize = 11.sp, color = TextPrimary, fontFamily = FontFamily.Monospace)
                }
                Divider(color = BorderColor.copy(alpha = 0.4f), thickness = 0.5.dp)
            }
        }
    }
}

// ─── Benchmark Tab ────────────────────────────────────────────────────────────
@Composable
fun BenchmarkTab(modelLoaded: Boolean, benchResult: String, isBenching: Boolean, onRunBench: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(12.dp))
        Text("⚡ Performance Benchmark",
            color = AccentCyan, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text("Measures prompt-processing (PP) and token-generation (TG) speed.",
            color = TextSecond, fontSize = 12.sp, textAlign = TextAlign.Center)

        Button(
            onClick  = onRunBench, enabled = modelLoaded && !isBenching,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape    = RoundedCornerShape(12.dp),
            colors   = ButtonDefaults.buttonColors(containerColor = AccentAmber)
        ) {
            if (isBenching) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp),
                    color = Color.Black, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (isBenching) "Benchmarking…" else "▶ Run Benchmark (PP=512, TG=128)",
                color = Color.Black, fontWeight = FontWeight.Bold)
        }

        if (!modelLoaded) Text("Load a model first.", color = AccentRed, fontSize = 13.sp)

        var ppTps    = 0.0; var tgTps = 0.0; var parseError = false
        try {
            if (benchResult.isNotEmpty()) {
                val obj = JSONObject(benchResult)
                ppTps = obj.optDouble("pp_tps", 0.0)
                tgTps = obj.optDouble("tg_tps", 0.0)
            }
        } catch (_: Exception) { parseError = true }

        if (benchResult.isNotEmpty() && !parseError && (ppTps > 0 || tgTps > 0)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BenchCard("Prompt Process", "%.1f".format(ppTps), "tokens/sec")
                BenchCard("Token Generate", "%.1f".format(tgTps), "tokens/sec")
            }
        } else if (benchResult.isNotEmpty()) {
            Surface(color = BgCard, shape = RoundedCornerShape(12.dp)) {
                Text(benchResult, modifier = Modifier.padding(12.dp),
                    color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun BenchCard(label: String, value: String, unit: String) {
    Surface(
        color = BgCard, shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderColor),
        modifier = Modifier.width(155.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = 30.sp, fontWeight = FontWeight.Black,
                color = AccentCyan, fontFamily = FontFamily.Monospace)
            Text(unit,  fontSize = 10.sp, color = TextSecond)
            Spacer(Modifier.height(4.dp))
            Text(label, fontSize = 11.sp, color = TextPrimary, textAlign = TextAlign.Center)
        }
    }
}


