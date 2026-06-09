package com.gguf.ipc

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ── Design Tokens ──
private val BgDeep       = Color(0xFF080C14)
private val BgSurface    = Color(0xFF111827)
private val BgCard       = Color(0xFF1A1F2E)
private val BgInput      = Color(0xFF0F172A)
private val BgThink      = Color(0xFF0F172A)
private val UserBubble   = Color(0xFF1E3A5F)
private val BotBubble    = Color(0xFF14291A)
val AccentCyan           = Color(0xFF22D3EE)
val AccentGreen          = Color(0xFF34D399)
val AccentAmber          = Color(0xFFFBBF24)
val AccentRed            = Color(0xFFF87171)
val AccentPurple         = Color(0xFFA78BFA)
private val TextPrimary  = Color(0xFFF0F4F8)
private val TextSecond   = Color(0xFF94A3B8)
private val TextMuted    = Color(0xFF475569)
private val NavInactive  = Color(0xFF475569)

// ── Data ──
enum class Role { USER, ASSISTANT }

data class ChatMessage(
    val role: Role,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val tokensPerSec: Float = 0f,
    val tokenCount: Int = 0
)

private enum class Screen(val label: String, val icon: ImageVector, val filledIcon: ImageVector) {
    CHAT("Chat", Icons.Outlined.Forum, Icons.Filled.Forum),
    MODELS("Models", Icons.Outlined.Memory, Icons.Filled.Memory),
    SETTINGS("Settings", Icons.Outlined.Tune, Icons.Filled.Tune),
    INFO("Info", Icons.Outlined.Info, Icons.Filled.Info),
    BENCH("Bench", Icons.Outlined.Speed, Icons.Filled.Speed)
}

// ── Activity ──
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EngineCore.bootZeroCopyEngine()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background   = BgDeep,
                    surface      = BgSurface,
                    primary      = AccentCyan,
                    secondary    = AccentPurple,
                    tertiary     = AccentGreen,
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
        contentResolver.openInputStream(uri)?.use { input ->
            onProgress("Copying model to internal storage\u2026")
            cacheFile.outputStream().use { input.copyTo(it, bufferSize = 8 * 1024 * 1024) }
        }
        cacheFile.absolutePath
    } catch (e: Exception) { null }
}

// ── App Root ──
@Composable
private fun AppRoot() {
    val ctx         = LocalContext.current
    val activity    = ctx as MainActivity
    val scope       = rememberCoroutineScope()
    val listState   = rememberLazyListState()
    val clipManager = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val chatManager = remember { ChatManager(ctx) }

    // ── State ──
    var screen          by remember { mutableStateOf(Screen.CHAT) }
    var engineStatus    by remember { mutableStateOf("No model loaded") }
    var isLoading       by remember { mutableStateOf(false) }
    var isInferring     by remember { mutableStateOf(false) }
    var modelLoaded     by remember { mutableStateOf(false) }
    var modelFilename   by remember { mutableStateOf("") }
    var modelPath       by remember { mutableStateOf("") }
    var modelInfo       by remember { mutableStateOf<JSONObject?>(null) }
    var streamedText    by remember { mutableStateOf("") }
    var promptInput     by remember { mutableStateOf("") }
    var kvUsage         by remember { mutableStateOf(0) }
    var tokensPerSec    by remember { mutableStateOf(0f) }
    var inferStartMs    by remember { mutableStateOf(0L) }
    var benchResult     by remember { mutableStateOf("") }
    var isBenching      by remember { mutableStateOf(false) }
    var showSessionMenu by remember { mutableStateOf(false) }
    var renameDialogId  by remember { mutableStateOf<String?>(null) }
    var renameText      by remember { mutableStateOf("") }

    // ── Settings state ──
    var nCtxStr      by remember { mutableStateOf("8192") }
    var maxTokStr    by remember { mutableStateOf("4096") }
    var tempStr      by remember { mutableStateOf("0.7") }
    var topPStr      by remember { mutableStateOf("0.9") }
    var minPStr      by remember { mutableStateOf("0.05") }
    var gpuLStr      by remember { mutableStateOf("99") }
    var threadsStr   by remember { mutableStateOf("4") }
    var repPenStr    by remember { mutableStateOf("1.1") }
    var freqPenStr   by remember { mutableStateOf("0.0") }
    var presPenStr   by remember { mutableStateOf("0.0") }
    var sysPrompt    by remember { mutableStateOf("You are a helpful, concise assistant running on-device. Respond clearly and directly.") }

    // CPU / device info
    var cpuInfo  by remember { mutableStateOf<DeviceUtils.CpuInfo?>(null) }
    var gpuInfo  by remember { mutableStateOf<DeviceUtils.GpuInfo?>(null) }

    val chatHistory = remember { mutableStateListOf<ChatMessage>() }

    // Load chat messages when session changes
    fun loadChatSession() {
        chatHistory.clear()
        val msgs = chatManager.loadMessagesForCurrent()
        chatHistory.addAll(msgs)
    }

    // Init: auto-detect device, load first session
    LaunchedEffect(Unit) {
        autoDetectAndApply()
        loadChatSession()
    }

    // ── Apply config to native ──
    fun applyConfig() {
        EngineCore.setEngineConfig(EngineCore.Config(
            nCtx         = nCtxStr.toIntOrNull()?.coerceIn(512, 32768) ?: 8192,
            maxNewTokens = maxTokStr.toIntOrNull()?.coerceIn(64, 8192) ?: 4096,
            temperature  = tempStr.toFloatOrNull()?.coerceIn(0f, 2f) ?: 0.7f,
            topP         = topPStr.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.9f,
            minP         = minPStr.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.05f,
            nGpuLayers   = gpuLStr.toIntOrNull()?.coerceIn(0, 999) ?: 99,
            nThreads     = threadsStr.toIntOrNull()?.coerceIn(1, 16) ?: 4,
            seed         = -1
        ))
        EngineCore.setSystemPromptNative(sysPrompt)
        EngineCore.setRepeatPenalty(EngineCore.RepeatPenaltyConfig(
            repeatPenalty = repPenStr.toFloatOrNull() ?: 1.1f,
            freqPenalty   = freqPenStr.toFloatOrNull() ?: 0.0f,
            presPenalty   = presPenStr.toFloatOrNull() ?: 0.0f
        ))
    }

    // ── Auto-detect CPU and apply ──
    fun autoDetectAndApply() {
        val cpu = DeviceUtils.detectCpu()
        val gpu = DeviceUtils.detectGpu()
        cpuInfo = cpu
        gpuInfo = gpu
        threadsStr = cpu.suggestedThreads.toString()
        gpuLStr = cpu.suggestedGpuLayers.toString()
        applyConfig()
        engineStatus = "Auto-configured: ${cpu.cores}c ${cpu.architecture} ${cpu.suggestedGpuLayers}GL"
    }

    // ── Inference polling ──
    LaunchedEffect(isInferring) {
        if (isInferring) {
            inferStartMs = System.currentTimeMillis()
            while (isInferring) {
                delay(80)
                val partial = EngineCore.readPartialStream()
                if (partial.isNotEmpty()) streamedText += partial
                val elapsed = (System.currentTimeMillis() - inferStartMs) / 1000f
                val toks    = EngineCore.getTokensGenerated()
                if (elapsed > 0f) tokensPerSec = toks / elapsed
                kvUsage = EngineCore.getKvCacheUsageNative()
                if (EngineCore.isInferenceDone()) {
                    delay(30)
                    val finalText = EngineCore.readTokenStream()
                    val finalToks = EngineCore.getTokensGenerated()
                    val finalTps  = if (elapsed > 0f) finalToks / elapsed else 0f
                    EngineCore.addTotalTokens(finalToks)
                    if (finalText.isNotEmpty()) {
                        val msg = ChatMessage(Role.ASSISTANT, finalText,
                            tokensPerSec = finalTps, tokenCount = finalToks)
                        chatHistory.add(msg)
                        chatManager.addMessage(msg)
                        // Auto-name session on first response
                        val session = chatManager.currentSession
                        if (session != null && session.name == "New Chat") {
                            chatManager.renameSession(session.id, session.autoName())
                        }
                    }
                    streamedText = ""
                    isInferring  = false
                }
            }
        }
    }

    // Auto-scroll
    LaunchedEffect(chatHistory.size, isInferring) {
        if (chatHistory.isNotEmpty() && !isInferring)
            listState.animateScrollToItem(chatHistory.size - 1)
    }

    // ── File picker (model) ──
    val modelPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val name = uri.lastPathSegment?.substringAfterLast('/')
                    ?.substringAfterLast(':') ?: "model.gguf"
                isLoading = true; modelLoaded = false; streamedText = ""
                engineStatus = "Preparing\u2026"
                scope.launch(Dispatchers.IO) {
                    val cached = activity.copyUriToFiles(uri, name) { msg ->
                        scope.launch(Dispatchers.Main) { engineStatus = msg }
                    }
                    if (cached == null) {
                        withContext(Dispatchers.Main) {
                            engineStatus = "Failed to copy model file"
                            isLoading = false
                        }
                        return@launch
                    }
                    withContext(Dispatchers.Main) { engineStatus = "Loading into GGML\u2026" }
                    applyConfig()
                    val ok = EngineCore.loadModel(cached)
                    if (ok) {
                        modelInfo = try { JSONObject(EngineCore.getModelInfoNative()) }
                            catch (_: Exception) { null }
                    }
                    withContext(Dispatchers.Main) {
                        isLoading = false
                        modelLoaded = ok
                        modelFilename = name
                        if (ok) modelPath = cached else modelPath = ""
                        engineStatus = if (ok) "\u2713 $name" else "\u2717 Load failed (OOM?)"
                        if (ok) screen = Screen.CHAT
                    }
                }
            }
        }
    }

    // ── File picker (import presets) ──
    val presetPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    val text = activity.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                    if (text != null) {
                        val json = JSONObject(text)
                        nCtxStr = json.optString("n_ctx", nCtxStr)
                        maxTokStr = json.optString("max_new_tokens", maxTokStr)
                        tempStr = json.optString("temperature", tempStr)
                        topPStr = json.optString("top_p", topPStr)
                        minPStr = json.optString("min_p", minPStr)
                        gpuLStr = json.optString("n_gpu_layers", gpuLStr)
                        threadsStr = json.optString("n_threads", threadsStr)
                        repPenStr = json.optString("repeat_penalty", repPenStr)
                        freqPenStr = json.optString("freq_penalty", freqPenStr)
                        presPenStr = json.optString("pres_penalty", presPenStr)
                        json.optString("system_prompt", "").takeIf { it.isNotEmpty() }?.let { sysPrompt = it }
                        applyConfig()
                        engineStatus = "Preset loaded \u2713"
                    }
                } catch (e: Exception) {
                    engineStatus = "\u2717 Failed to load preset"
                }
            }
        }
    }

    // ── Rename dialog ──
    if (renameDialogId != null) {
        AlertDialog(
            onDismissRequest = { renameDialogId = null },
            containerColor = BgCard,
            titleContentColor = AccentCyan,
            textContentColor = TextPrimary,
            title = { Text("Rename Chat") },
            text = {
                OutlinedTextField(
                    value = renameText, onValueChange = { renameText = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentCyan,
                        unfocusedBorderColor = Color(0xFF334155),
                        focusedTextColor = TextPrimary,
                        cursorColor = AccentCyan
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    chatManager.renameSession(renameDialogId!!, renameText)
                    renameDialogId = null
                }) { Text("Rename", color = AccentCyan) }
            },
            dismissButton = {
                TextButton(onClick = { renameDialogId = null }) { Text("Cancel", color = TextMuted) }
            }
        )
    }

    // ── Session switcher modal ──
    if (showSessionMenu) {
        AlertDialog(
            onDismissRequest = { showSessionMenu = false },
            containerColor = BgCard,
            titleContentColor = AccentCyan,
            title = { Text("Chat Sessions") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(chatManager.allSessions) { session ->
                        val isCurrent = session.id == chatManager.currentSessionId
                        Surface(
                            modifier = Modifier.fillMaxWidth()
                                .clickable {
                                    if (!isCurrent) {
                                        chatManager.switchTo(session.id)
                                        loadChatSession()
                                    }
                                    showSessionMenu = false
                                },
                            color = if (isCurrent) AccentCyan.copy(alpha = 0.08f) else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (isCurrent) Icons.Filled.Forum else Icons.Outlined.Forum,
                                    contentDescription = null,
                                    tint = if (isCurrent) AccentCyan else TextSecond,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(session.name, fontSize = 13.sp, color = TextPrimary,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${session.messages.size} msgs",
                                        fontSize = 10.sp, color = TextMuted)
                                }
                                IconButton(onClick = {
                                    renameText = session.name
                                    renameDialogId = session.id
                                    showSessionMenu = false
                                }) {
                                    Icon(Icons.Outlined.Edit, null, tint = TextMuted,
                                        modifier = Modifier.size(16.dp))
                                }
                                IconButton(onClick = { chatManager.deleteSession(session.id) }) {
                                    Icon(Icons.Outlined.Delete, null, tint = AccentRed,
                                        modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                    item {
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                chatManager.createSession()
                                loadChatSession()
                                showSessionMenu = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                        ) { Text("+ New Chat", color = Color.Black) }
                    }
                }
            },
            confirmButton = {}
        )
    }

    // ── Layout ──
    Scaffold(
        containerColor = BgDeep,
        topBar = {
            AppTopBar(
                engineStatus = engineStatus,
                modelLoaded  = modelLoaded,
                isInferring  = isInferring,
                kvUsage      = kvUsage,
                tokensPerSec = tokensPerSec,
                isLoading    = isLoading,
                sessionName  = chatManager.currentSession?.name ?: "Chat",
                onLoadModel = {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "*/*"))
                    }
                    modelPicker.launch(intent)
                },
                onSessions  = { showSessionMenu = true },
                onSettings  = { }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = BgSurface,
                contentColor   = AccentCyan
            ) {
                Screen.entries.forEach { s ->
                    NavigationBarItem(
                        selected = screen == s,
                        onClick  = { screen = s },
                        icon     = {
                            Icon(
                                if (screen == s) s.filledIcon else s.icon,
                                contentDescription = s.label
                            )
                        },
                        label    = { Text(s.label, fontSize = 11.sp) },
                        colors   = NavigationBarItemDefaults.colors(
                            selectedIconColor = AccentCyan,
                            selectedTextColor = AccentCyan,
                            unselectedIconColor = NavInactive,
                            unselectedTextColor = NavInactive,
                            indicatorColor     = AccentCyan.copy(alpha = 0.12f)
                        )
                    )
                }
            }
        }
    ) { pad ->
        Box(modifier = Modifier.fillMaxSize().padding(pad)) {
            when (screen) {
                Screen.CHAT -> ChatScreen(
                    chatHistory  = chatHistory,
                    listState    = listState,
                    streamedText = streamedText,
                    isInferring  = isInferring,
                    isLoading    = isLoading,
                    modelLoaded  = modelLoaded,
                    promptInput  = promptInput,
                    sessionName  = chatManager.currentSession?.name ?: "Chat",
                    sessionCount = chatManager.allSessions.size,
                    onPromptChange = { promptInput = it },
                    onSend = {
                        if (!isInferring && promptInput.isNotBlank()) {
                            val userMsg = ChatMessage(Role.USER, promptInput)
                            chatHistory.add(userMsg)
                            chatManager.addMessage(userMsg)
                            val msg = promptInput
                            promptInput = ""
                            streamedText = ""
                            isInferring = true
                            scope.launch(Dispatchers.IO) {
                                EngineCore.executeZeroCopyInference(msg)
                            }
                        }
                    },
                    onAbort = { EngineCore.abortInferenceNative() },
                    onReset = {
                        EngineCore.resetContextNative()
                        chatManager.clearCurrentSession()
                        chatHistory.clear()
                        streamedText = ""
                        engineStatus = "Context reset \u2713"
                    },
                    onCopyChat = {
                        val sb = StringBuilder()
                        chatHistory.forEach { m ->
                            val tag = if (m.role == Role.USER) "User" else "Assistant"
                            sb.append("[$tag] ${m.content}\n\n")
                        }
                        clipManager.setPrimaryClip(ClipData.newPlainText("Chat", sb.toString()))
                    },
                    onNewSession = {
                        chatManager.createSession()
                        loadChatSession()
                    },
                    onSwitchSession = { idx ->
                        chatManager.switchToIndex(idx)
                        loadChatSession()
                    },
                    clipManager = clipManager
                )

                Screen.MODELS -> ModelsScreen(
                    modelLoaded  = modelLoaded,
                    modelFilename = modelFilename,
                    modelInfo    = modelInfo,
                    engineStatus = engineStatus,
                    cpuInfo      = cpuInfo,
                    gpuInfo      = gpuInfo,
                    onLoadModel  = {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "*/*"))
                        }
                        modelPicker.launch(intent)
                    },
                    onAutoDetect = { autoDetectAndApply() }
                )

                Screen.SETTINGS -> SettingsScreen(
                    nCtxStr = nCtxStr, onNCtxChange = { nCtxStr = it },
                    maxTokStr = maxTokStr, onMaxTokChange = { maxTokStr = it },
                    tempStr = tempStr, onTempChange = { tempStr = it },
                    topPStr = topPStr, onTopPChange = { topPStr = it },
                    minPStr = minPStr, onMinPChange = { minPStr = it },
                    gpuLStr = gpuLStr, onGpuLChange = { gpuLStr = it },
                    threadsStr = threadsStr, onThreadsChange = { threadsStr = it },
                    repPenStr = repPenStr, onRepPenChange = { repPenStr = it },
                    freqPenStr = freqPenStr, onFreqPenChange = { freqPenStr = it },
                    presPenStr = presPenStr, onPresPenChange = { presPenStr = it },
                    sysPrompt = sysPrompt, onSysPromptChange = { sysPrompt = it },
                    cpuInfo = cpuInfo, gpuInfo = gpuInfo,
                    onAutoDetect = { autoDetectAndApply() },
                    onImportPreset = {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "*/*"))
                        }
                        presetPicker.launch(intent)
                    },
                    onApply = {
                        applyConfig()
                        if (modelLoaded && modelPath.isNotEmpty()) {
                            scope.launch(Dispatchers.IO) {
                                withContext(Dispatchers.Main) {
                                    engineStatus = "Reloading model\u2026"
                                    isLoading = true
                                }
                                val ok = EngineCore.loadModel(modelPath)
                                if (ok) {
                                    modelInfo = try { JSONObject(EngineCore.getModelInfoNative()) }
                                        catch (_: Exception) { null }
                                }
                                withContext(Dispatchers.Main) {
                                    isLoading = false
                                    modelLoaded = ok
                                    engineStatus = if (ok) "\u2713 Settings applied, $modelFilename"
                                        else "\u2717 Reload failed"
                                }
                            }
                        } else {
                            engineStatus = "Settings saved (load model first)"
                        }
                    }
                )

                Screen.INFO -> InfoScreen(
                    modelLoaded  = modelLoaded,
                    modelInfo    = modelInfo,
                    modelFilename = modelFilename,
                    totalTokens  = EngineCore.totalTokens(),
                    cpuInfo      = cpuInfo,
                    gpuInfo      = gpuInfo
                )

                Screen.BENCH -> BenchScreen(
                    modelLoaded = modelLoaded,
                    benchResult = benchResult,
                    isBenching  = isBenching,
                    onRunBench  = {
                        if (!isInferring && modelLoaded) {
                            isBenching = true
                            scope.launch(Dispatchers.IO) {
                                val raw = EngineCore.benchmarkNative(512, 128)
                                withContext(Dispatchers.Main) {
                                    benchResult = raw
                                    isBenching = false
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}

// ── Top Bar ──
@Composable
private fun AppTopBar(
    engineStatus: String, modelLoaded: Boolean, isInferring: Boolean,
    kvUsage: Int, tokensPerSec: Float, isLoading: Boolean,
    sessionName: String,
    onLoadModel: () -> Unit, onSessions: () -> Unit, onSettings: () -> Unit
) {
    Surface(color = BgSurface, shadowElevation = 2.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("GGUF ZC v5",
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            color = AccentCyan, fontSize = 16.sp)
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(AccentCyan.copy(alpha = 0.12f))
                                .clickable { onSessions() }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(sessionName,
                                color = AccentCyan, fontSize = 9.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 120.dp))
                        }
                    }
                    Text(engineStatus,
                        color = if (modelLoaded) AccentGreen else TextSecond,
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 1.dp))
                }
                if (modelLoaded) {
                    StatChip("KV", "$kvUsage%", if (kvUsage > 80) AccentRed else AccentGreen)
                    Spacer(Modifier.width(6.dp))
                    if (isInferring)
                        StatChip("TPS", "${"%.1f".format(tokensPerSec)}", AccentAmber)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onLoadModel,
                    enabled = !isLoading && !isInferring,
                    modifier = Modifier.height(32.dp),
                    colors  = ButtonDefaults.buttonColors(
                        containerColor = if (modelLoaded) BgCard else AccentCyan)
                ) {
                    Text(
                        when { isLoading -> "Loading\u2026"
                            modelLoaded -> "\u27F3 Swap"
                            else -> "\uD83D\uDCC2 Load GGUF" },
                        color = if (modelLoaded) AccentCyan else Color.Black,
                        fontSize = 11.sp
                    )
                }
                if (isInferring) {
                    Button(
                        onClick = { EngineCore.abortInferenceNative() },
                        modifier = Modifier.height(32.dp),
                        colors   = ButtonDefaults.buttonColors(containerColor = AccentRed)
                    ) { Text("\u25A0 Stop", fontSize = 11.sp, color = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(BgInput)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 9.sp, color = TextSecond, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(3.dp))
        Text(value, fontSize = 9.sp, color = color, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold)
    }
}

// ── Chat Screen ──
@Composable
private fun ChatScreen(
    chatHistory: List<ChatMessage>, listState: LazyListState,
    streamedText: String, isInferring: Boolean, isLoading: Boolean,
    modelLoaded: Boolean, promptInput: String,
    sessionName: String, sessionCount: Int,
    onPromptChange: (String) -> Unit, onSend: () -> Unit,
    onAbort: () -> Unit, onReset: () -> Unit, onCopyChat: () -> Unit,
    onNewSession: () -> Unit, onSwitchSession: (Int) -> Unit,
    clipManager: ClipboardManager
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Messages
        LazyColumn(
            state    = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (chatHistory.isEmpty() && !modelLoaded && !isLoading) {
                item { WelcomeCard() }
            }

            items(chatHistory, key = { "${it.role}_${it.timestamp}" }) { msg ->
                MessageBubble(msg) { content ->
                    clipManager.setPrimaryClip(ClipData.newPlainText("Message", content))
                }
            }

            if (isInferring && streamedText.isNotEmpty()) {
                item(key = "streaming") {
                    StreamingBubble(streamedText)
                }
            }

            if (isLoading) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = AccentCyan, strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Loading model\u2026", color = TextSecond, fontSize = 13.sp)
                    }
                }
            }
        }

        // Input bar
        Surface(color = BgSurface, shadowElevation = 8.dp) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                OutlinedTextField(
                    value = promptInput,
                    onValueChange = onPromptChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            if (modelLoaded) "Type a message\u2026" else "Load a model first",
                            color = TextMuted, fontSize = 13.sp
                        )
                    },
                    enabled = modelLoaded && !isLoading,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = AccentCyan,
                        unfocusedBorderColor = Color(0xFF334155),
                        focusedTextColor     = TextPrimary,
                        unfocusedTextColor   = TextPrimary,
                        disabledTextColor    = TextSecond,
                        disabledBorderColor  = Color(0xFF1E293B),
                        cursorColor          = AccentCyan
                    ),
                    maxLines = 4,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() })
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = if (isInferring) onAbort else onSend,
                        enabled = modelLoaded && !isLoading &&
                            (isInferring || promptInput.isNotBlank()),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isInferring) AccentRed else AccentCyan)
                    ) {
                        Text(
                            if (isInferring) "\u25A0 Stop" else "\u25B6 Send",
                            color = Color.Black, fontWeight = FontWeight.Bold
                        )
                    }
                    OutlinedButton(
                        onClick = onReset,
                        enabled = modelLoaded && !isInferring,
                        colors  = ButtonDefaults.outlinedButtonColors(contentColor = AccentAmber)
                    ) { Text("\u21BA Reset", fontSize = 11.sp) }
                    OutlinedButton(
                        onClick = onCopyChat,
                        enabled = chatHistory.isNotEmpty(),
                        colors  = ButtonDefaults.outlinedButtonColors(contentColor = TextMuted)
                    ) { Text("\u2398 Copy", fontSize = 11.sp) }
                }
            }
        }
    }
}

@Composable
private fun WelcomeCard() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(
                    Brush.sweepGradient(listOf(AccentCyan, AccentPurple, AccentGreen, AccentCyan))
                ),
            contentAlignment = Alignment.Center
        ) {
            Text("AI", fontSize = 24.sp, color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
        Text("GGUF ZeroCopy v5",
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            color = AccentCyan, fontSize = 20.sp)
        Spacer(Modifier.height(8.dp))
        Text("Load a .gguf model to start chatting",
            color = TextSecond, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text("Vulkan GPU \u00B7 Zero-copy IPC \u00B7 Q8_0 KV-Cache",
            color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage, onCopy: (String) -> Unit) {
    val isUser = msg.role == Role.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            Avatar(AccentCyan, "AI")
            Spacer(Modifier.width(6.dp))
        }
        Column(modifier = Modifier.widthIn(max = 300.dp)) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(
                        topStart = if (isUser) 16.dp else 4.dp,
                        topEnd   = if (isUser) 4.dp else 16.dp,
                        bottomStart = 16.dp, bottomEnd = 16.dp
                    ))
                    .background(if (isUser) UserBubble else BotBubble)
                    .clickable { onCopy(msg.content) }
                    .padding(12.dp)
            ) {
                if (isUser) {
                    Text(msg.content, color = TextPrimary, fontSize = 14.sp, lineHeight = 20.sp)
                } else {
                    AssistantContent(msg.content)
                }
            }
            Row(
                modifier = Modifier.padding(top = 3.dp, start = 4.dp, end = 4.dp),
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
            ) {
                val ts = SimpleDateFormat("HH:mm", Locale.getDefault())
                    .format(Date(msg.timestamp))
                Text(ts, color = TextMuted, fontSize = 10.sp)
                if (!isUser && msg.tokensPerSec > 0f) {
                    Spacer(Modifier.width(8.dp))
                    Text("${"%.1f".format(msg.tokensPerSec)} t/s \u00B7 ${msg.tokenCount} tok",
                        color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        if (isUser) {
            Spacer(Modifier.width(6.dp))
            Avatar(AccentGreen, "U")
        }
    }
}

@Composable
private fun Avatar(color: Color, text: String) {
    Box(
        modifier = Modifier.size(28.dp).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 10.sp, color = Color.Black, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AssistantContent(text: String) {
    val hasThink    = text.contains("<think>")
    val thinkClosed = text.contains("</think>")
    val thought     = if (hasThink) {
        text.substringAfter("<think>")
            .let { if (thinkClosed) it.substringBefore("</think>") else it }
    } else ""
    val response = when {
        hasThink && thinkClosed -> text.substringAfter("</think>").trimStart()
        hasThink                -> ""
        else                    -> text
    }

    Column {
        if (thought.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            Surface(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { expanded = !expanded },
                color = BgThink
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("\uD83E\uDDE0", fontSize = 12.sp)
                        Spacer(Modifier.width(4.dp))
                        Text(if (expanded) "Hide reasoning" else "Show reasoning",
                            fontSize = 11.sp, color = AccentPurple,
                            fontFamily = FontFamily.Monospace)
                        if (!thinkClosed) {
                            Spacer(Modifier.width(6.dp))
                            CircularProgressIndicator(
                                modifier = Modifier.size(9.dp),
                                color = AccentPurple, strokeWidth = 1.5.dp
                            )
                        }
                    }
                    AnimatedVisibility(visible = expanded) {
                        Text(thought, fontSize = 12.sp, color = AccentPurple.copy(alpha = 0.7f),
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            if (response.isNotEmpty()) Spacer(Modifier.height(6.dp))
        }
        if (response.isNotEmpty()) {
            Text(response, color = AccentGreen.copy(alpha = 0.9f), fontSize = 14.sp,
                lineHeight = 21.sp)
        }
    }
}

@Composable
private fun StreamingBubble(text: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f, label = "cursorAlpha",
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse)
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Avatar(AccentCyan, "AI")
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp,
                    bottomStart = 16.dp, bottomEnd = 16.dp))
                .background(BotBubble)
                .padding(12.dp)
        ) {
            Column {
                RichTextContent(text)
                Spacer(Modifier.width(4.dp))
                Text("\u258A", color = AccentCyan.copy(alpha = alpha), fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun RichTextContent(text: String) {
    val parts = text.split("```")
    parts.forEachIndexed { i, part ->
        if (i % 2 == 0) {
            Text(part, color = AccentGreen.copy(alpha = 0.85f), fontSize = 14.sp, lineHeight = 20.sp)
        } else {
            val lines = part.split("\n")
            val lang = if (lines.isNotEmpty() && lines[0].all { it.isLetterOrDigit() || it == '#' }) lines[0] else ""
            val code = if (lang.isNotEmpty()) lines.drop(1).joinToString("\n") else part
            Surface(
                color = BgInput,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Text(
                    code.trimEnd(),
                    color = AccentCyan,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(10.dp),
                    lineHeight = 17.sp
                )
            }
        }
    }
}

// ── Models Screen ──
@Composable
private fun ModelsScreen(
    modelLoaded: Boolean, modelFilename: String,
    modelInfo: JSONObject?, engineStatus: String,
    cpuInfo: DeviceUtils.CpuInfo?, gpuInfo: DeviceUtils.GpuInfo?,
    onLoadModel: () -> Unit, onAutoDetect: () -> Unit
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Model Manager", fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold, color = AccentCyan, fontSize = 16.sp)

        Button(
            onClick = onLoadModel,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
        ) { Text("\uD83D\uDCC2 Load GGUF Model", color = Color.Black, fontWeight = FontWeight.Bold) }

        // Quick device info card
        Surface(color = BgCard, shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("Device Auto-Detect", fontWeight = FontWeight.SemiBold,
                    color = AccentCyan, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(8.dp))
                if (cpuInfo != null) {
                    InfoRow("CPU Cores", "${cpuInfo.cores}")
                    InfoRow("Arch", cpuInfo.architecture)
                    InfoRow("big.LITTLE", if (cpuInfo.isBigLittle) "Yes" else "No")
                    InfoRow("Max Freq", "${cpuInfo.maxFrequencyMHz} MHz")
                    InfoRow("Suggested", "${cpuInfo.suggestedThreads}t ${cpuInfo.suggestedGpuLayers}GL")
                } else {
                    Text("Tap 'Auto-Detect' to scan your device",
                        fontSize = 12.sp, color = TextSecond)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onAutoDetect,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentGreen)
                ) { Text("\u2699 Auto-Detect & Configure", fontSize = 12.sp) }
            }
        }

        if (!modelLoaded) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("\uD83E\uDD16", fontSize = 48.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("No model loaded", color = TextSecond, fontSize = 14.sp)
                    Text("Tap the button above to select a .gguf file",
                        color = TextMuted, fontSize = 12.sp)
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = BgCard)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Loaded Model", fontWeight = FontWeight.SemiBold,
                        color = AccentCyan, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    InfoRow("File", modelFilename)
                    InfoRow("Status", engineStatus)
                    if (modelInfo != null) {
                        val keys = modelInfo.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            InfoRow(k.replace("_", " ").replaceFirstChar { it.uppercase() },
                                modelInfo.optString(k, "\u2014"))
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = BgCard)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Architecture", fontWeight = FontWeight.SemiBold,
                        color = AccentCyan, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("ASharedMemory ring buffer\n" +
                        "write_pos(4) | flags(4) | tokens_gen(4) | tps_scaled(4) | data(512KB)\n\n" +
                        "C++ ipc-bridge writes tokens as UTF-8 into data region.\n" +
                        "Kotlin polls isInferenceDone() every 80ms.\n" +
                        "Q8_0 KV-Cache quantization for 2x context density.",
                        fontSize = 11.sp, color = TextSecond, fontFamily = FontFamily.Monospace,
                        lineHeight = 18.sp)
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = BgCard)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Device", fontWeight = FontWeight.SemiBold,
                        color = AccentCyan, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    InfoRow("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    InfoRow("Android", "${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
                    val ram = (Runtime.getRuntime().totalMemory() / 1048576)
                    InfoRow("RAM (App)", "${ram} MB")
                    if (cpuInfo != null) {
                        InfoRow("CPU Cores", "${cpuInfo.cores}")
                        InfoRow("Features", cpuInfo.features.take(4).joinToString(" "))
                    }
                    val freeStorage = runCatching {
                        val stat = StatFs(Environment.getDataDirectory().path)
                        stat.availableBytes / 1073741824
                    }.getOrNull()
                    if (freeStorage != null) {
                        InfoRow("Free Storage", "${freeStorage} GB")
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, fontSize = 12.sp, color = TextSecond, modifier = Modifier.weight(1f))
        Text(value, fontSize = 12.sp, color = TextPrimary,
            fontFamily = FontFamily.Monospace)
    }
}

// ── Settings Screen ──
@Composable
private fun SettingsScreen(
    nCtxStr: String, onNCtxChange: (String) -> Unit,
    maxTokStr: String, onMaxTokChange: (String) -> Unit,
    tempStr: String, onTempChange: (String) -> Unit,
    topPStr: String, onTopPChange: (String) -> Unit,
    minPStr: String, onMinPChange: (String) -> Unit,
    gpuLStr: String, onGpuLChange: (String) -> Unit,
    threadsStr: String, onThreadsChange: (String) -> Unit,
    repPenStr: String, onRepPenChange: (String) -> Unit,
    freqPenStr: String, onFreqPenChange: (String) -> Unit,
    presPenStr: String, onPresPenChange: (String) -> Unit,
    sysPrompt: String, onSysPromptChange: (String) -> Unit,
    cpuInfo: DeviceUtils.CpuInfo?, gpuInfo: DeviceUtils.GpuInfo?,
    onAutoDetect: () -> Unit, onImportPreset: () -> Unit, onApply: () -> Unit
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // CPU Detection Card
        SettingsHeader("Device & Performance")
        Surface(color = BgCard, shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (cpuInfo != null) {
                    InfoRow("CPU", "${cpuInfo.cores} cores | ${cpuInfo.architecture}")
                    if (cpuInfo.cpuPartNames.isNotEmpty())
                        InfoRow("Part", cpuInfo.cpuPartNames.joinToString(" "))
                    InfoRow("big.LITTLE", if (cpuInfo.isBigLittle) "Yes \u2014 auto-tuned" else "No")
                    InfoRow("Max Freq", "${cpuInfo.maxFrequencyMHz} MHz")
                    InfoRow("Threads", "${cpuInfo.suggestedThreads} (suggested)")
                    InfoRow("GPU Layers", "${cpuInfo.suggestedGpuLayers} (suggested)")
                    if (cpuInfo.features.isNotEmpty())
                        InfoRow("Features", cpuInfo.features.take(5).joinToString(" "))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onAutoDetect,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentGreen)
                    ) { Text("\u26A1 Auto-Detect", fontSize = 11.sp) }
                    OutlinedButton(
                        onClick = onImportPreset,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentPurple)
                    ) { Text("\uD83D\uDCC2 Import Preset", fontSize = 11.sp) }
                }
            }
        }

        SettingsHeader("Context & Generation")
        SettingsCard {
            SettingField("Context Window", "512\u201332768", nCtxStr, onNCtxChange)
            SettingField("Max New Tokens", "64\u20138192", maxTokStr, onMaxTokChange)
            SettingField("GPU Layers", "99=GPU, 0=CPU", gpuLStr, onGpuLChange)
            SettingField("CPU Threads", "1\u201316", threadsStr, onThreadsChange)
        }

        SettingsHeader("Sampling")
        SettingsCard {
            SettingField("Temperature", "0\u20132.0", tempStr, onTempChange)
            SettingField("Top-P", "0\u20131.0", topPStr, onTopPChange)
            SettingField("Min-P", "0\u20131.0", minPStr, onMinPChange)
        }

        SettingsHeader("Repetition Penalties")
        SettingsCard {
            SettingField("Repeat Penalty", "1.0=off", repPenStr, onRepPenChange)
            SettingField("Frequency Penalty", "0.0=off", freqPenStr, onFreqPenChange)
            SettingField("Presence Penalty", "0.0=off", presPenStr, onPresPenChange)
        }

        SettingsHeader("System Prompt")
        OutlinedTextField(
            value = sysPrompt,
            onValueChange = onSysPromptChange,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 5,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentCyan,
                unfocusedBorderColor = Color(0xFF334155),
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = AccentCyan
            ),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 12.sp, fontFamily = FontFamily.Monospace
            )
        )

        SettingsHeader("Quick Presets")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "Qwen3"     to Triple("8192", "0.6", "You are a helpful assistant."),
                "Gemma 4"   to Triple("8192", "0.7", "You are a helpful assistant."),
                "Reasoning" to Triple("16384","0.6", "Think step-by-step before answering."),
                "Creative"  to Triple("8192", "1.0", "You are a creative storyteller.")
            ).forEach { (label, v) ->
                OutlinedButton(
                    onClick = {
                        onNCtxChange(v.first)
                        onTempChange(v.second)
                        onSysPromptChange(v.third)
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentPurple)
                ) { Text(label, fontSize = 10.sp) }
            }
        }

        Button(
            onClick = onApply,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
        ) { Text("Apply Settings", color = Color.Black, fontWeight = FontWeight.Bold) }

        Text("\u26A0 Apply Settings reloads the model so all changes take effect immediately.",
            fontSize = 11.sp, color = AccentAmber, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun SettingsHeader(title: String) {
    Text(title, fontSize = 13.sp, color = AccentCyan,
        fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = BgCard,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            content()
        }
    }
}

@Composable
private fun SettingField(label: String, hint: String, value: String, onChange: (String) -> Unit) {
    Column {
        Text(label, fontSize = 12.sp, color = TextSecond, fontWeight = FontWeight.Medium)
        Text(hint, fontSize = 10.sp, color = TextMuted)
        OutlinedTextField(
            value = value, onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentCyan,
                unfocusedBorderColor = Color(0xFF334155),
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = AccentCyan
            ),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 13.sp, fontFamily = FontFamily.Monospace
            )
        )
    }
}

// ── Info Screen ──
@Composable
private fun InfoScreen(
    modelLoaded: Boolean, modelInfo: JSONObject?,
    modelFilename: String, totalTokens: Int,
    cpuInfo: DeviceUtils.CpuInfo?, gpuInfo: DeviceUtils.GpuInfo?
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Device & Model Info", fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold, color = AccentCyan, fontSize = 16.sp)

        // CPU/Device info
        InfoCard("Device") {
            InfoRow("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
            InfoRow("Android", "${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
            if (cpuInfo != null) {
                InfoRow("CPU Cores", "${cpuInfo.cores}")
                InfoRow("Architecture", cpuInfo.architecture)
                InfoRow("big.LITTLE", if (cpuInfo.isBigLittle) "Yes" else "No")
                InfoRow("Max Freq", "${cpuInfo.maxFrequencyMHz} MHz")
                if (cpuInfo.features.isNotEmpty())
                    InfoRow("Features", cpuInfo.features.joinToString(" "))
            }
            if (gpuInfo != null) {
                InfoRow("GPU", gpuInfo.renderer)
                InfoRow("Vendor", gpuInfo.vendor)
                InfoRow("Vulkan", if (gpuInfo.hasVulkan) "Available" else "N/A")
            }
            val ram = (Runtime.getRuntime().totalMemory() / 1048576)
            InfoRow("RAM (App)", "${ram} MB")
        }

        if (!modelLoaded) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                contentAlignment = Alignment.Center) {
                Text("Load a model to see more information", color = TextSecond)
            }
            return
        }

        InfoCard("Model") {
            InfoRow("File", modelFilename)
        }

        modelInfo?.let { info ->
            InfoCard("Metadata") {
                val keys = info.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    InfoRow(k.replace("_", " ")
                        .replaceFirstChar { it.uppercase() }, info.optString(k, "\u2014"))
                }
            }
        }

        InfoCard("Session") {
            InfoRow("Total Tokens", "$totalTokens")
        }

        InfoCard("Architecture") {
            InfoRow("Memory", "ASharedMemory ring buffer (512 KB)")
            InfoRow("KV Cache", "Q8_0 quantization (2x density)")
            InfoRow("IPC", "Zero-copy shared memory")
            InfoRow("GPU", "Vulkan Unified Memory")
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = BgCard, shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, fontSize = 13.sp, color = AccentCyan,
                fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

// ── Benchmark Screen ──
@Composable
private fun BenchScreen(
    modelLoaded: Boolean, benchResult: String,
    isBenching: Boolean, onRunBench: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(8.dp))
        Text("Performance Benchmark", color = AccentCyan,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text("Measures prompt-processing (PP) and token-generation (TG) speed.",
            color = TextSecond, fontSize = 12.sp, textAlign = TextAlign.Center)

        Button(
            onClick = onRunBench,
            enabled = modelLoaded && !isBenching,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AccentAmber)
        ) {
            if (isBenching) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp),
                    color = Color.Black, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (isBenching) "Benchmarking\u2026" else "\u25B6 Run Benchmark (PP=512, TG=128)",
                color = Color.Black, fontWeight = FontWeight.Bold)
        }

        if (!modelLoaded)
            Text("Load a model first.", color = AccentRed, fontSize = 13.sp)

        var ppTps = 0.0; var tgTps = 0.0; var ppMs = 0.0; var tgMs = 0.0
        var parseError = false
        try {
            if (benchResult.isNotEmpty()) {
                val obj = JSONObject(benchResult)
                ppTps = obj.optDouble("pp_tps", 0.0)
                tgTps = obj.optDouble("tg_tps", 0.0)
                ppMs  = obj.optDouble("pp_ms", 0.0)
                tgMs  = obj.optDouble("tg_ms", 0.0)
            }
        } catch (_: Exception) { parseError = true }

        if (benchResult.isNotEmpty() && !parseError && (ppTps > 0.0 || tgTps > 0.0)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BenchCard("Prompt\nProcessing", "${"%.1f".format(ppTps)} t/s",
                    "${"%.0f".format(ppMs)} ms")
                BenchCard("Token\nGeneration", "${"%.1f".format(tgTps)} t/s",
                    "${"%.0f".format(tgMs)} ms")
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
private fun BenchCard(title: String, value: String, subtitle: String) {
    Surface(
        color  = BgCard,
        shape  = RoundedCornerShape(16.dp),
        modifier = Modifier.width(150.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                color = AccentCyan, fontFamily = FontFamily.Monospace)
            Text(subtitle, fontSize = 11.sp, color = TextSecond)
            Spacer(Modifier.height(4.dp))
            Text(title, fontSize = 12.sp, color = TextPrimary,
                textAlign = TextAlign.Center, lineHeight = 16.sp)
        }
    }
}
