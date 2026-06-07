package com.gguf.ipc

import android.app.Activity
import android.content.Intent
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EngineCore.bootZeroCopyEngine()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF00F2FF))) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF05070A)) {
                    UltraScreen()
                }
            }
        }
    }

    fun copyModel(uri: android.net.Uri): String? = try {
        val file = File(filesDir, "model.gguf")
        contentResolver.openInputStream(uri)?.use { it.copyTo(file.outputStream()) }
        file.absolutePath
    } catch (e: Exception) { null }
}

@Composable
fun UltraScreen() {
    val activity = LocalContext.current as MainActivity
    val scope = rememberCoroutineScope()
    var output by remember { mutableStateOf("> SYSTEM READY\n> AWAITING CORE...") }
    var input by remember { mutableStateOf("") }
    var tps by remember { mutableFloatStateOf(0f) }
    var isRunning by remember { mutableStateOf(false) }
    var modelLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(150)
            if (modelLoaded) {
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
                    if (path != null && EngineCore.loadModel(path)) modelLoaded = true
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("GGUF ULTRA v5", color = Color(0xFF00F2FF), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(if (modelLoaded) "CORE ONLINE" else "CORE OFFLINE", color = if (modelLoaded) Color.Green else Color.Red, fontSize = 10.sp)
            }
            Box(Modifier.border(1.dp, Color(0xFF00F2FF), RoundedCornerShape(4.dp)).padding(8.dp)) {
                Text("${"%.1f".format(tps)} TPS", color = Color(0xFF00F2FF), fontFamily = FontFamily.Monospace)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp)
            .background(Color(0xFF10141D), RoundedCornerShape(12.dp))
            .border(0.5.dp, Color.White.copy(0.1f), RoundedCornerShape(12.dp))
            .padding(16.dp).verticalScroll(rememberScrollState())) {
            Text(output, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        }

        OutlinedTextField(
            value = input, onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("CMD_PROMPT >", color = Color.DarkGray) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF00F2FF),
                unfocusedBorderColor = Color(0xFF1E293B),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            )
        )

        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { 
                    val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
                    picker.launch(i)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10141D))
            ) { Text("LOAD_GGUF", color = Color(0xFF00F2FF)) }

            Button(
                onClick = { 
                    isRunning = true
                    scope.launch(Dispatchers.IO) { EngineCore.executeZeroCopyInference(input) }
                    input = ""
                },
                enabled = modelLoaded && !isRunning,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00F2FF))
            ) { Text("EXECUTE", color = Color.Black, fontWeight = FontWeight.Bold) }
        }
    }
}

