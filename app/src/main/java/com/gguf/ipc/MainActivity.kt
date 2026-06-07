package com.gguf.ipc

import android.app.Activity
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import java.io.File

// Futuristic Theme
private val NeonCyan = Color(0xFF00FBFF)
private val DarkVoid = Color(0xFF020408)
private val CyberGrey = Color(0xFF10141D)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EngineCore.bootZeroCopyEngine()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = NeonCyan)) {
                UltraEngineScreen()
            }
        }
    }

    fun copyModel(uri: android.net.Uri): String? = try {
        val file = File(filesDir, "active_model.gguf")
        contentResolver.openInputStream(uri)?.use { it.copyTo(file.outputStream()) }
        file.absolutePath
    } catch (e: Exception) { null }
}

@Composable
fun UltraEngineScreen() {
    val activity = LocalContext.current as MainActivity
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var output by remember { mutableStateOf("> SYSTEM IDLE\n> AWAITING COMMAND...") }
    var input by remember { mutableStateOf("") }
    var tps by remember { mutableFloatStateOf(0f) }
    var isRunning by remember { mutableStateOf(false) }
    var modelReady by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(100)
            if (modelReady) {
                output = EngineCore.readPartialStream()
                tps = EngineCore.getTpsScaled()
                if (isRunning && EngineCore.isInferenceDone()) isRunning = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            it.data?.data?.let { uri ->
                scope.launch(Dispatchers.IO) {
                    val path = activity.copyModel(uri)
                    if (path != null && EngineCore.loadModel(path)) modelReady = true
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(DarkVoid).padding(16.dp)) {
        // Holographic Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("GGUF ULTRA v5", color = NeonCyan, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Text(if (modelReady) "CORE ONLINE" else "CORE OFFLINE", color = if (modelReady) Color.Green else Color.Red, fontSize = 10.sp)
            }
            // TPS Card
            Box(Modifier.border(1.dp, NeonCyan, RoundedCornerShape(4.dp)).padding(8.dp)) {
                Text("${"%.1f".format(tps)} TPS", color = NeonCyan, fontFamily = FontFamily.Monospace)
            }
        }

        Spacer(Modifier.height(20.dp))

        // Matrix Console Output
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(CyberGrey).border(0.5.dp, NeonCyan.copy(0.3f), RoundedCornerShape(8.dp))
            .padding(12.dp).verticalScroll(rememberScrollState())) {
            Text(output, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        }

        Spacer(Modifier.height(12.dp))

        // Cyber Input
        OutlinedTextField(
            value = input, onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontFamily = FontFamily.Monospace),
            placeholder = { Text("CMD_PROMPT >", color = Color.Gray) },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NeonCyan, unfocusedBorderColor = Color.DarkGray)
        )

        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { 
                    val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
                    picker.launch(i)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = CyberGrey)
            ) { Text("LOAD_CORE", color = NeonCyan) }

            Button(
                onClick = { 
                    isRunning = true
                    scope.launch(Dispatchers.IO) { EngineCore.executeZeroCopyInference(input) }
                    input = ""
                },
                enabled = modelReady && !isRunning,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
            ) { Text("EXECUTE", color = Color.Black, fontWeight = FontWeight.Bold) }
        }
    }
}


