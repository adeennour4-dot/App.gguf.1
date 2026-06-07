
package com.gguf.ipc

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import java.io.File

// Design Palette
private val CyanNeon = Color(0xFF00F2FF)
private val BgDeep = Color(0xFF05070A)
private val BgCard = Color(0xFF10141D)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EngineCore.bootZeroCopyEngine()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = CyanNeon, surface = BgCard)) {
                Surface(modifier = Modifier.fillMaxSize(), color = BgDeep) {
                    GgufProV5Screen()
                }
            }
        }
    }

    // Helper to move GGUF from Downloads to Private Storage
    fun copyUriToFiles(uri: Uri, filename: String): String? = try {
        val cacheFile = File(filesDir, filename)
        contentResolver.openInputStream(uri)?.use { input ->
            cacheFile.outputStream().use { output -> input.copyTo(output) }
        }
        cacheFile.absolutePath
    } catch (e: Exception) { null }
}

@Composable
fun GgufProV5Screen() {
    val activity = LocalContext.current as MainActivity
    val coroutineScope = rememberCoroutineScope()
    
    // UI State
    var selectedTab by remember { mutableIntStateOf(0) }
    var engineStatus by remember { mutableStateOf("No Model Loaded") }
    var modelLoaded by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var isInferring by remember { mutableStateOf(false) }
    
    // Telemetry State
    var outputText by remember { mutableStateOf("Awaiting Model...") }
    var tps by remember { mutableFloatStateOf(0f) }
    var kvUsage by remember { mutableIntStateOf(0) }

    // Settings State
    var nCtx by remember { mutableStateOf("4096") }
    var temp by remember { mutableStateOf("0.7") }
    var gpuLayers by remember { mutableStateOf("0") }

    // Polling Loop
    LaunchedEffect(Unit) {
        while (true) {
            delay(150)
            if (modelLoaded) {
                outputText = EngineCore.readPartialStream()
                tps = EngineCore.getTpsScaled()
                kvUsage = EngineCore.getKvCacheUsageNative()
                if (isInferring && EngineCore.isInferenceDone()) isInferring = false
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                isLoading = true
                engineStatus = "Mapping Model..."
                coroutineScope.launch(Dispatchers.IO) {
                    val path = activity.copyUriToFiles(uri, "model.gguf")
                    if (path != null) {
                        val success = EngineCore.loadModel(path)
                        withContext(Dispatchers.Main) {
                            modelLoaded = success
                            isLoading = false
                            engineStatus = if (success) "✓ Model Active" else "✗ Load Failed"
                        }
                    }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // --- TOP BAR ---
        Box(Modifier.fillMaxWidth().background(BgCard).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("GGUF PRO v5", color = CyanNeon, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(engineStatus, color = Color.Gray, fontSize = 11.sp)
                }
                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }
                        filePicker.launch(intent)
                    },
                    enabled = !isLoading && !isInferring,
                    colors = ButtonDefaults.buttonColors(containerColor = CyanNeon),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(if (isLoading) "LOADING..." else "LOAD GGUF", color = Color.Black, fontSize = 12.sp)
                }
            }
        }

        // --- TAB BAR ---
        TabRow(selectedTabIndex = selectedTab, containerColor = BgCard, contentColor = CyanNeon) {
            val tabs = listOf("CHAT", "SETTINGS", "INFO")
            tabs.forEachIndexed { index, title ->
                Tab(selected = selectedTab == index, onClick = { selectedTab = index }) {
                    Text(title, modifier = Modifier.padding(12.dp), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // --- CONTENT ---
        when (selectedTab) {
            0 -> ChatTab(outputText, tps, kvUsage, isInferring, modelLoaded, onSend = { prompt ->
                if (!isInferring && modelLoaded) {
                    isInferring = true
                    coroutineScope.launch(Dispatchers.IO) { EngineCore.executeZeroCopyInference(prompt) }
                }
            })
            1 -> SettingsTab(nCtx, temp, gpuLayers, onNCtxChange = {nCtx = it}, onTempChange = {temp = it}, onGpuChange = {gpuLayers = it})
            2 -> InfoTab(kvUsage)
        }
    }
}

@Composable
fun ChatTab(output: String, tps: Float, kv: Int, inferring: Boolean, loaded: Boolean, onSend: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row {
            Text("TELEMETRY: ${"%.1f".format(tps)} TPS", color = CyanNeon, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            Text("KV: $kv%", color = CyanNeon, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp)
            .background(BgCard, RoundedCornerShape(8.dp))
            .border(0.5.dp, Color.White.copy(0.1f), RoundedCornerShape(8.dp))
            .padding(12.dp).verticalScroll(rememberScrollState())) {
            Text(output, color = Color.White, fontSize = 15.sp)
        }

        OutlinedTextField(
            value = input, onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = loaded,
            placeholder = { Text("Enter prompt...", color = Color.DarkGray) },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyanNeon, focusedTextColor = Color.White, unfocusedTextColor = Color.White)
        )
        
        Button(
            onClick = { onSend(input); input = "" },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            enabled = loaded && !inferring,
            colors = ButtonDefaults.buttonColors(containerColor = CyanNeon)
        ) {
            Text(if (inferring) "ENGINE BUSY" else "EXECUTE", color = Color.Black)
        }
    }
}

@Composable
fun SettingsTab(nCtx: String, temp: String, gpu: String, onNCtxChange: (String) -> Unit, onTempChange: (String) -> Unit, onGpuChange: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("ENGINE CONFIGURATION", color = CyanNeon, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        
        Text("Context Size (n_ctx)", color = Color.Gray, fontSize = 12.sp)
        OutlinedTextField(value = nCtx, onValueChange = onNCtxChange, modifier = Modifier.fillMaxWidth())
        
        Spacer(Modifier.height(8.dp))
        Text("Temperature", color = Color.Gray, fontSize = 12.sp)
        OutlinedTextField(value = temp, onValueChange = onTempChange, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(8.dp))
        Text("GPU Layers (Vulkan)", color = Color.Gray, fontSize = 12.sp)
        OutlinedTextField(value = gpu, onValueChange = onGpuChange, modifier = Modifier.fillMaxWidth())
        
        Spacer(Modifier.height(16.dp))
        Text("Note: Model reload required for n_ctx and GPU changes.", color = Color.Yellow.copy(0.7f), fontSize = 10.sp)
    }
}

@Composable
fun InfoTab(kv: Int) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("SYSTEM INFORMATION", color = CyanNeon, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text("Architecture: llama.cpp b9542", color = Color.White)
        Text("Backend: CPU (ARMv8.4 DotProd)", color = Color.White)
        Text("Shared Memory: 512KB Ring Buffer", color = Color.White)
        Text("KV-Cache Usage: $kv%", color = Color.White)
    }
}
