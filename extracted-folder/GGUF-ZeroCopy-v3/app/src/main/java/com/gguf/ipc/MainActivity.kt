package com.gguf.ipc

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream

// ---------------------------------------------------------------------------
// Colours
// ---------------------------------------------------------------------------
private val DarkBackground = Color(0xFF0F0F0F)
private val SurfaceColor   = Color(0xFF1A1A1A)
private val AccentGreen    = Color(0xFF00E5A0)
private val AccentBlue     = Color(0xFF00BFFF)
private val ThinkBg        = Color(0xFF1C2030)
private val SettingsBg     = Color(0xFF151520)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EngineCore.bootZeroCopyEngine()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background   = DarkBackground,
                    surface      = SurfaceColor,
                    primary      = AccentGreen,
                    onBackground = Color.White,
                    onSurface    = Color.White
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = DarkBackground) {
                    GgufEngineScreen()
                }
            }
        }
    }

    fun copyUriToCache(uri: Uri, filename: String): String? = try {
        val cacheFile = File(cacheDir, filename)
        contentResolver.openInputStream(uri)?.use { input: InputStream ->
            cacheFile.outputStream().use { input.copyTo(it, bufferSize = 8 * 1024 * 1024) }
        }
        cacheFile.absolutePath
    } catch (e: Exception) { null }
}

// ---------------------------------------------------------------------------
// Main screen
// ---------------------------------------------------------------------------
@Composable
fun GgufEngineScreen() {
    val activity       = androidx.compose.ui.platform.LocalContext.current as MainActivity
    val coroutineScope = rememberCoroutineScope()
    val listState      = rememberLazyListState()

    // Model state
    var engineStatus by remember { mutableStateOf("Awaiting .gguf model…") }
    var isLoading    by remember { mutableStateOf(false) }
    var isInferring  by remember { mutableStateOf(false) }
    var modelLoaded  by remember { mutableStateOf(false) }
    var streamedText by remember { mutableStateOf("") }
    var promptInput  by remember { mutableStateOf("Hello! Who are you and what can you do?") }

    // Settings panel
    var showSettings   by remember { mutableStateOf(false) }
    var nCtxStr        by remember { mutableStateOf("8192") }
    var maxTokensStr   by remember { mutableStateOf("4096") }
    var tempStr        by remember { mutableStateOf("0.7") }
    var topPStr        by remember { mutableStateOf("0.9") }
    var minPStr        by remember { mutableStateOf("0.05") }
    var gpuLayersStr   by remember { mutableStateOf("99") }
    var systemPrompt   by remember { mutableStateOf(
        "You are a helpful, concise assistant running on-device. Respond clearly and directly."
    )}

    // Apply settings helper
    fun applySettings() {
        val cfg = EngineCore.Config(
            nCtx         = nCtxStr.toIntOrNull()?.coerceIn(512, 32768) ?: 8192,
            maxNewTokens = maxTokensStr.toIntOrNull()?.coerceIn(64, 8192) ?: 4096,
            temperature  = tempStr.toFloatOrNull()?.coerceIn(0f, 2f)   ?: 0.7f,
            topP         = topPStr.toFloatOrNull()?.coerceIn(0f, 1f)   ?: 0.9f,
            minP         = minPStr.toFloatOrNull()?.coerceIn(0f, 1f)   ?: 0.05f,
            nGpuLayers   = gpuLayersStr.toIntOrNull()?.coerceIn(0, 999)?: 99,
            seed         = -1
        )
        EngineCore.setEngineConfig(cfg)
        EngineCore.setSystemPromptNative(systemPrompt)
    }

    // Stream polling
    LaunchedEffect(isInferring) {
        if (isInferring) {
            while (isInferring) {
                delay(80)
                val partial = EngineCore.readPartialStream()
                if (partial.isNotEmpty()) streamedText = partial
                if (EngineCore.isInferenceDone()) {
                    streamedText = EngineCore.readTokenStream()
                    isInferring = false
                }
            }
        }
    }

    LaunchedEffect(streamedText) {
        if (streamedText.isNotEmpty())
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount.coerceAtLeast(0))
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val filename = uri.lastPathSegment
                    ?.substringAfterLast('/')?.substringAfterLast(':') ?: "model.gguf"
                isLoading = true; modelLoaded = false; streamedText = ""
                engineStatus = "Copying model to cache…"
                coroutineScope.launch(Dispatchers.IO) {
                    val cachedPath = activity.copyUriToCache(uri, filename)
                    if (cachedPath == null) {
                        withContext(Dispatchers.Main) { engineStatus = "Error: Could not read model file."; isLoading = false }
                        return@launch
                    }
                    withContext(Dispatchers.Main) { engineStatus = "Loading model into GGML / Vulkan backend…" }
                    // Apply settings before loading
                    applySettings()
                    val success = EngineCore.loadModel(cachedPath)
                    withContext(Dispatchers.Main) {
                        isLoading = false; modelLoaded = success
                        engineStatus = if (success) "✓ Active: $filename" else "✗ Load failed — check Logcat (OOM?)"
                    }
                }
            }
        }
    }

    Scaffold(
        containerColor = DarkBackground,
        bottomBar = {
            Column(modifier = Modifier.background(SurfaceColor).padding(12.dp)) {
                if (modelLoaded) {
                    OutlinedTextField(
                        value = promptInput,
                        onValueChange = { promptInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Prompt", color = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = AccentGreen, unfocusedBorderColor = Color.DarkGray,
                            focusedTextColor     = Color.White, unfocusedTextColor   = Color.White
                        ),
                        maxLines = 4
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (!isInferring && promptInput.isNotBlank()) {
                                    streamedText = ""; isInferring = true
                                    coroutineScope.launch(Dispatchers.IO) {
                                        EngineCore.executeZeroCopyInference(promptInput)
                                    }
                                }
                            },
                            enabled  = modelLoaded && !isInferring,
                            modifier = Modifier.weight(1f),
                            colors   = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                        ) {
                            Text(if (isInferring) "Generating…" else "▶  Run", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                        // Reset context button
                        OutlinedButton(
                            onClick = {
                                EngineCore.resetContextNative()
                                streamedText = ""
                                engineStatus = "✓ Context reset"
                            },
                            enabled  = modelLoaded && !isInferring,
                            modifier = Modifier.wrapContentWidth(),
                            colors   = ButtonDefaults.outlinedButtonColors(contentColor = AccentBlue)
                        ) { Text("↺ Reset", fontSize = 12.sp) }
                    }
                    Spacer(Modifier.height(4.dp))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "*/*"))
                            }
                            filePicker.launch(intent)
                        },
                        enabled  = !isLoading && !isInferring,
                        modifier = Modifier.weight(1f),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor = if (modelLoaded) Color(0xFF2A2A2A) else AccentBlue)
                    ) {
                        Text(
                            if (isLoading) "Loading…" else if (modelLoaded) "⟳ Load Model" else "📂 Load .GGUF",
                            color = Color.White
                        )
                    }
                    OutlinedButton(
                        onClick = { showSettings = !showSettings },
                        colors  = ButtonDefaults.outlinedButtonColors(contentColor = Color.Gray)
                    ) { Text(if (showSettings) "▲ Settings" else "⚙ Settings", fontSize = 12.sp) }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            state    = listState,
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
        ) {
            item {
                Spacer(Modifier.height(16.dp))
                Text("Zero-Copy GGUF Engine",
                    style = MaterialTheme.typography.titleLarge,
                    color = AccentGreen, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(engineStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = Color(0xFF2A2A2A))
                Spacer(Modifier.height(16.dp))
            }

            // Settings panel
            if (showSettings) {
                item {
                    SettingsPanel(
                        nCtxStr = nCtxStr,       onNCtxChange = { nCtxStr = it },
                        maxTokensStr = maxTokensStr, onMaxTokensChange = { maxTokensStr = it },
                        tempStr = tempStr,       onTempChange = { tempStr = it },
                        topPStr = topPStr,       onTopPChange = { topPStr = it },
                        minPStr = minPStr,       onMinPChange = { minPStr = it },
                        gpuLayersStr = gpuLayersStr, onGpuLayersChange = { gpuLayersStr = it },
                        systemPrompt = systemPrompt, onSystemPromptChange = { systemPrompt = it },
                        onApply = {
                            applySettings()
                            showSettings = false
                            if (modelLoaded) engineStatus = "✓ Settings applied (reload model to change n_ctx/gpu_layers)"
                        }
                    )
                }
            }

            if (isLoading) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = AccentBlue, strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Mapping tensors to Vulkan pipeline…", color = Color.Gray)
                    }
                }
            }

            if (streamedText.isNotEmpty()) {
                item { StreamRenderBubble(streamText = streamedText, isStreaming = isInferring) }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

// ---------------------------------------------------------------------------
// Settings panel composable
// ---------------------------------------------------------------------------
@Composable
fun SettingsPanel(
    nCtxStr: String,       onNCtxChange: (String) -> Unit,
    maxTokensStr: String,  onMaxTokensChange: (String) -> Unit,
    tempStr: String,       onTempChange: (String) -> Unit,
    topPStr: String,       onTopPChange: (String) -> Unit,
    minPStr: String,       onMinPChange: (String) -> Unit,
    gpuLayersStr: String,  onGpuLayersChange: (String) -> Unit,
    systemPrompt: String,  onSystemPromptChange: (String) -> Unit,
    onApply: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SettingsBg)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("⚙  Context & Sampling Settings",
            fontWeight = FontWeight.Bold, color = Color(0xFF00BFFF), fontFamily = FontFamily.Monospace)

        // Context window
        SettingRow("Context Window (n_ctx)", "512–32768. 8192 fits most 8B models.",
            nCtxStr, onNCtxChange)
        // Max new tokens
        SettingRow("Max New Tokens", "Max tokens the model generates per turn.",
            maxTokensStr, onMaxTokensChange)
        // Temperature
        SettingRow("Temperature", "0 = deterministic, 1.0 = creative. Try 0.6 for Qwen3/ZAYA.",
            tempStr, onTempChange)
        // Top-P
        SettingRow("Top-P", "Nucleus sampling. 0.9 is a safe default.",
            topPStr, onTopPChange)
        // Min-P
        SettingRow("Min-P", "Filters very low-probability tokens. 0.05 recommended.",
            minPStr, onMinPChange)
        // GPU layers
        SettingRow("GPU Layers (n_gpu_layers)", "99 = all on Vulkan GPU. Set 0 for CPU-only.",
            gpuLayersStr, onGpuLayersChange)

        // System prompt
        Text("System Prompt", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        OutlinedTextField(
            value = systemPrompt, onValueChange = onSystemPromptChange,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 5,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF00BFFF), unfocusedBorderColor = Color.DarkGray,
                focusedTextColor = Color.White, unfocusedTextColor = Color(0xFFCCCCCC)
            ),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        )

        // Preset buttons
        Text("Quick presets:", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "Qwen3/ZAYA" to Triple("8192", "0.6", "You are a helpful assistant."),
                "Gemma 4"    to Triple("8192", "0.7", "You are a helpful assistant."),
                "Reasoning"  to Triple("16384", "0.6", "You are a helpful reasoning assistant. Think step by step.")
            ).forEach { (label, vals) ->
                OutlinedButton(
                    onClick = {
                        onNCtxChange(vals.first)
                        onTempChange(vals.second)
                        onSystemPromptChange(vals.third)
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF8899BB))
                ) { Text(label, fontSize = 11.sp) }
            }
        }

        Button(
            onClick = onApply,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BFFF))
        ) { Text("Apply Settings", color = Color.Black, fontWeight = FontWeight.Bold) }

        Text(
            "⚠ n_ctx and GPU layers take effect on next model load. Temperature/Top-P apply immediately.",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF665500),
            modifier = Modifier.padding(top = 2.dp)
        )
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
fun SettingRow(label: String, hint: String, value: String, onChange: (String) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Text(hint, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = Color(0xFF556677))
        OutlinedTextField(
            value = value, onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF00BFFF), unfocusedBorderColor = Color.DarkGray,
                focusedTextColor = Color.White, unfocusedTextColor = Color.White
            ),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        )
    }
}

// ---------------------------------------------------------------------------
// Stream bubble with <think> accordion (unchanged from v2)
// ---------------------------------------------------------------------------
@Composable
fun StreamRenderBubble(streamText: String, isStreaming: Boolean = false) {
    val hasThink        = streamText.contains("<think>")
    val thinkClosed     = streamText.contains("</think>")
    val internalThought = if (hasThink) {
        streamText.substringAfter("<think>").let {
            if (thinkClosed) it.substringBefore("</think>") else it
        }
    } else ""
    val responseText = when {
        hasThink && thinkClosed -> streamText.substringAfter("</think>").trimStart()
        hasThink                -> ""
        else                    -> streamText
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).animateContentSize()) {
        if (internalThought.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(ThinkBg).clickable { expanded = !expanded }.padding(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🧠", fontSize = 13.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(if (expanded) "Hide chain-of-thought" else "Show chain-of-thought",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF8899BB), fontFamily = FontFamily.Monospace)
                    if (!thinkClosed) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(modifier = Modifier.size(10.dp),
                            color = AccentBlue, strokeWidth = 1.5.dp)
                    }
                }
                AnimatedVisibility(visible = expanded) {
                    Text(internalThought,
                        style = MaterialTheme.typography.bodySmall, color = Color(0xFF6677AA),
                        fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 8.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        if (responseText.isNotEmpty() || (!hasThink && isStreaming)) {
            Box(modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF1E2E1E)).padding(14.dp)) {
                Column {
                    Text(responseText,
                        style = MaterialTheme.typography.bodyMedium, color = Color(0xFFDDFFDD),
                        lineHeight = 22.sp)
                    if (isStreaming && responseText.isNotEmpty())
                        Text("▌", color = AccentGreen, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}
