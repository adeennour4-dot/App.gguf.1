package com.gguf.ipc

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import java.io.File

// Design Palette
private val TechnicalCyan = Color(0xFF00FBFF)
private val DeepOllamaDark = Color(0xFF0A0A0A)
private val TerminalGrey = Color(0xFF1A1A1A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EngineCore.bootZeroCopyEngine()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = TechnicalCyan)) {
                Surface(modifier = Modifier.fillMaxSize(), color = DeepOllamaDark) {
                    GGUFKernelApp()
                }
            }
        }
    }

    fun copyModel(uri: android.net.Uri): String? = try {
        val file = File(filesDir, "active_core.gguf")
        contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        file.absolutePath
    } catch (e: Exception) { null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GGUFKernelApp() {
    val activity = LocalContext.current as MainActivity
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scrollState = rememberScrollState()

    var output by remember { mutableStateOf("> KERNEL_INIT_OK\n> AWAITING NEURAL_LINK...") }
    var input by remember { mutableStateOf("") }
    var tps by remember { mutableFloatStateOf(0f) }
    var kvUsage by remember { mutableIntStateOf(0) }
    var isRunning by remember { mutableStateOf(false) }
    var modelLoaded by remember { mutableStateOf(false) }

    // Settings
    var nCtx by remember { mutableStateOf("4096") }
    var temp by remember { mutableFloatStateOf(0.7f) }

    LaunchedEffect(modelLoaded, isRunning) {
        while (true) {
            delay(120)
            if (modelLoaded) {
                output = EngineCore.readPartialStream()
                tps = EngineCore.getTpsScaled()
                kvUsage = EngineCore.getKvCacheUsageNative()
                if (isRunning && EngineCore.isInferenceDone()) isRunning = false
                if (output.length > 5) scrollState.animateScrollTo(scrollState.maxValue)
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == android.app.Activity.RESULT_OK) {
            it.data?.data?.let { uri ->
                scope.launch(Dispatchers.IO) {
                    val path = activity.copyModel(uri)
                    if (path != null && EngineCore.loadModel(path)) modelLoaded = true
                }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = TerminalGrey) {
                Column(Modifier.padding(24.dp)) {
                    Text("KERNEL_CONFIG", color = TechnicalCyan, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(20.dp))
                    Text("Context Window", color = Color.Gray, fontSize = 12.sp)
                    OutlinedTextField(value = nCtx, onValueChange = { nCtx = it }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(20.dp))
                    Text("Temperature: ${"%.2f".format(temp)}", color = Color.Gray, fontSize = 12.sp)
                    Slider(value = temp, onValueChange = { temp = it }, valueRange = 0.1f..1.5f)
                    Spacer(Modifier.height(40.dp))
                    Button(onClick = { 
                        EngineCore.resetContextNative()
                        output = "> CONTEXT_RESET_OK"
                    }, modifier = Modifier.fillMaxWidth()) { Text("RESET ENGINE") }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("GGUF TERMINAL", fontFamily = FontFamily.Monospace, fontSize = 14.sp) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = null, tint = TechnicalCyan)
                        }
                    },
                    actions = {
                        TextButton(onClick = { picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }) }) {
                            Text("LOAD", color = TechnicalCyan)
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = DeepOllamaDark)
                )
            },
            bottomBar = {
                Column(Modifier.background(DeepOllamaDark).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("CMD > ", color = TechnicalCyan, fontFamily = FontFamily.Monospace)
                        BasicTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 16.sp),
                            cursorBrush = SolidColor(TechnicalCyan)
                        )
                        if (isRunning) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            TextButton(onClick = { 
                                isRunning = true
                                scope.launch(Dispatchers.IO) { EngineCore.executeZeroCopyInference(input) }
                                input = ""
                            }, enabled = modelLoaded) { Text("RUN", color = TechnicalCyan) }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("TPS: ${"%.1f".format(tps)}", color = Color.DarkGray, fontSize = 10.sp)
                        Text("KV: $kvUsage%", color = Color.DarkGray, fontSize = 10.sp)
                    }
                }
            }
        ) { p ->
            Box(Modifier.padding(p).fillMaxSize().background(DeepOllamaDark).verticalScroll(scrollState).padding(16.dp)) {
                Text(output, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            }
        }
    }
}
