package com.gguf.ipc

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import java.io.File

// Cyber Palette
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
        contentResolver.openInputStream(uri)?.use { it.copyTo(file.outputStream()) }
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

    // Engine States
    var output by remember { mutableStateOf("> KERNEL_INIT_OK\n> AWAITING NEURAL_LINK...") }
    var input by remember { mutableStateOf("") }
    var tps by remember { mutableFloatStateOf(0f) }
    var kvUsage by remember { mutableIntStateOf(0) }
    var isRunning by remember { mutableStateOf(false) }
    var modelLoaded by remember { mutableStateOf(false) }

    // Settings States (Side Panel)
    var nCtx by remember { mutableStateOf("4096") }
    var temp by remember { mutableStateOf("0.7") }
    var nThreads by remember { mutableStateOf("4") }

    // Polling Loop
    LaunchedEffect(modelLoaded) {
        while (true) {
            delay(100)
            if (modelLoaded) {
                output = EngineCore.readPartialStream()
                tps = EngineCore.getTpsScaled()
                kvUsage = EngineCore.getKvCacheUsageNative()
                if (isRunning && EngineCore.isInferenceDone()) isRunning = false
                if (output.isNotEmpty()) scrollState.animateScrollTo(scrollState.maxValue)
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
            ModalDrawerSheet(
                drawerContainerColor = TerminalGrey,
                drawerContentColor = Color.White
            ) {
                Column(Modifier.padding(20.dp).fillMaxHeight()) {
                    Text("KERNEL_CONFIG", color = TechnicalCyan, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.height(24.dp))
                    
                    Text("Context window (n_ctx)", color = Color.Gray, fontSize = 12.sp)
                    OutlinedTextField(value = nCtx, onValueChange = { nCtx = it }, modifier = Modifier.fillMaxWidth())
                    
                    Spacer(Modifier.height(16.dp))
                    Text("Sampling Temperature", color = Color.Gray, fontSize = 12.sp)
                    Slider(value = temp.toFloatOrNull() ?: 0.7f, onValueChange = { temp = it.toString() }, valueRange = 0.1f..1.5f)
                    
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { EngineCore.resetContextNative(); output = "> CONTEXT_CLEARED" }, modifier = Modifier.fillMaxWidth()) {
                        Text("RESET_CONTEXT")
                    }
                    
                    Spacer(Modifier.weight(1f))
                    Text("VER: 5.0_ULTRA_PRO", color = Color.DarkGray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("GGUF TERMINAL", fontSize = 16.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Settings", tint = TechnicalCyan)
                        }
                    },
                    actions = {
                        IconButton(onClick = { 
                            picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" })
                        }) {
                            Text("LOAD", color = TechnicalCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = DeepOllamaDark, titleContentColor = Color.White)
                )
            },
            bottomBar = {
                // Technical Command Input
                Column(Modifier.background(DeepOllamaDark).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("CMD > ", color = TechnicalCyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        BasicTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 16.sp),
                            cursorBrush = SolidColor(TechnicalCyan)
                        )
                        if (!isRunning) {
                            TextButton(onClick = { 
                                isRunning = true
                                scope.launch(Dispatchers.IO) { EngineCore.executeZeroCopyInference(input) }
                                input = ""
                            }, enabled = modelLoaded) {
                                Text("RUN", color = TechnicalCyan, fontWeight = FontWeight.ExtraBold)
                            }
                        } else {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = TechnicalCyan)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // Telemetry Bar
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("TPS: ${"%.1f".format(tps)}", color = Color.DarkGray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text("KV: $kvUsage%", color = Color.DarkGray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        ) { innerPadding ->
            // Matrix Terminal Display
            Box(Modifier.padding(innerPadding).fillMaxSize().background(DeepOllamaDark)) {
                Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(scrollState)) {
                    Text(
                        text = output,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                    // Bouncing neural cursor
                    if (isRunning) {
                        BlinkingCursor()
                    }
                }
            }
        }
    }
}

@Composable
fun BlinkingCursor() {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "blink"
    )
    Box(Modifier.size(8.dp, 16.dp).background(TechnicalCyan.copy(alpha = alpha)))
}
