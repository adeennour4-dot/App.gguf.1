package com.gguf.ipc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EngineCore.bootZeroCopyEngine()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF00F2FF))) {
                ProScreen()
            }
        }
    }
}

@Composable
fun ProScreen() {
    var output by remember { mutableStateOf("Ready for Command...") }
    var input by remember { mutableStateOf("") }
    var tps by remember { mutableStateOf(0f) }
    var kv by remember { mutableIntOf(0) }
    var isRunning by remember { mutableStateOf(false) }

    LaunchedEffect(isRunning) {
        while (true) {
            delay(100)
            output = EngineCore.readPartialStream()
            tps = EngineCore.getTpsScaled()
            kv = EngineCore.getKvCacheUsageNative()
            if (isRunning && EngineCore.isInferenceDone()) isRunning = false
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF05070A)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("GGUF V5 PRO", color = Color(0xFF00F2FF), fontSize = 20.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            Text("${"%.1f".format(tps)} TPS", color = Color(0xFF00F2FF), fontSize = 12.sp)
        }
        
        LinearProgressIndicator(progress = { kv / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), color = Color(0xFF00F2FF))

        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF10141D), RoundedCornerShape(12.dp)).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text(output, color = Color.White, fontSize = 14.sp)
        }

        OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Enter Prompt", color = Color.Gray) })
        
        Button(
            onClick = { isRunning = true; CoroutineScope(Dispatchers.IO).launch { EngineCore.executeZeroCopyInference(input) } },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00F2FF))
        ) {
            Text("EXECUTE", color = Color.Black)
        }
    }
}
