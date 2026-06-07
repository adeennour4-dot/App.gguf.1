package com.gguf.ipc

import android.app.Activity
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
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF00FBFF))) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF020408)) {
                    UltraProScreen()
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

@Composable
fun UltraProScreen() {
    val activity = LocalContext.current as MainActivity
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    
    var output by remember { mutableStateOf("> KERNEL_READY\n> AWAITING LINK...") }
    var input by remember { mutableStateOf("") }
    var tps by remember { mutableFloatStateOf(0f) }
    var modelLoaded by remember { mutableStateOf(false) }
    var isRunning by remember { mutableStateOf(false) }

    // CORRECTED: Breathing neon animation logic
    val infiniteTransition = rememberInfiniteTransition(label = "neon")
    val neonColor by infiniteTransition.animateColor(
        initialValue = Color(0xFF00FBFF),
        targetValue = if (isRunning) Color(0xFFFF00AE) else Color(0xFF00FBFF),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "neonColor"
    )

    LaunchedEffect(modelLoaded, isRunning) {
        while (true) {
            delay(120)
            if (modelLoaded) {
                output = EngineCore.readPartialStream()
                tps = EngineCore.getTpsScaled()
                if (isRunning && EngineCore.isInferenceDone()) isRunning = false
                if (output.isNotEmpty()) scrollState.animateScrollTo(scrollState.maxValue)
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

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("GGUF ULTRA PRO", color = neonColor, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Text(if (modelLoaded) "SYNC_ESTABLISHED" else "LINK_REQUIRED", color = neonColor.copy(0.5f), fontSize = 10.sp)
            }
            Box(Modifier.border(1.dp, neonColor, RoundedCornerShape(4.dp)).padding(8.dp)) {
                Text("${"%.1f".format(tps)} TPS", color = neonColor, fontFamily = FontFamily.Monospace)
            }
        }

        Spacer(Modifier.height(20.dp))

        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF080C14)).border(1.dp, neonColor.copy(0.2f))
            .padding(15.dp).verticalScroll(scrollState)) {
            Text(output, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        }

        Spacer(Modifier.height(15.dp))

        OutlinedTextField(
            value = input, onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = neonColor, unfocusedBorderColor = Color.DarkGray)
        )

        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }) },
                modifier = Modifier.weight(0.4f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10141D))) {
                Text("UPLOAD")
            }
            Button(onClick = { isRunning = true; scope.launch(Dispatchers.IO) { EngineCore.executeZeroCopyInference(input) }; input = "" },
                enabled = modelLoaded && !isRunning, modifier = Modifier.weight(0.6f), colors = ButtonDefaults.buttonColors(containerColor = neonColor)) {
                Text("EXECUTE", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}
