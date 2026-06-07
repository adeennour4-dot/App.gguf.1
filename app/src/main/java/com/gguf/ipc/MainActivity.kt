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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Boot the native engine
        EngineCore.bootZeroCopyEngine()
        
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF00F2FF))) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF05070A)) {
                    ProScreen()
                }
            }
        }
    }
}

@Composable
fun ProScreen() {
    val coroutineScope = rememberCoroutineScope()
    var output by remember { mutableStateOf("System Ready. Awaiting Command...") }
    var input by remember { mutableStateOf("") }
    var tps by remember { mutableFloatStateOf(0f) }
    var kvUsage by remember { mutableIntStateOf(0) }
    var isRunning by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(150) // Update UI every 150ms
            output = EngineCore.readPartialStream()
            tps = EngineCore.getTpsScaled()
            kvUsage = EngineCore.getKvCacheUsageNative()
            
            if (isRunning && EngineCore.isInferenceDone()) {
                isRunning = false
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("GGUF V5 PRO", color = Color(0xFF00F2FF), fontSize = 20.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text("VULKAN/DOTPROD ENABLED", color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            Surface(color = Color(0xFF00F2FF).copy(0.1f), border = BorderStroke(1.dp, Color(0xFF00F2FF)), shape = RoundedCornerShape(4.dp)) {
                Text("${"%.1f".format(tps)} TPS", color = Color(0xFF00F2FF), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
        
        Spacer(Modifier.height(12.dp))
        
        // KV Progress
        Text("KV-CACHE: $kvUsage%", color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        LinearProgressIndicator(progress = { kvUsage / 100f }, modifier = Modifier.fillMaxWidth().height(4.dp), color = Color(0xFF00F2FF), trackColor = Color(0xFF10141D))

        // Terminal Output
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp)
            .background(Color(0xFF10141D), RoundedCornerShape(12.dp))
            .border(0.5.dp, Color.White.copy(0.1f), RoundedCornerShape(12.dp))
            .padding(16.dp).verticalScroll(rememberScrollState())) {
            Text(output, color = Color.White, fontSize = 15.sp)
        }

        // Input Area - FIX: Fixed typo 'focuse' and argument order
        OutlinedTextField(
            value = input, 
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Enter prompt...", color = Color.DarkGray) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF00F2FF),
                unfocusedBorderColor = Color(0xFF1E293B),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            shape = RoundedCornerShape(8.dp)
        )
        
        Button(
            onClick = { 
                if (!isRunning && input.isNotBlank()) {
                    isRunning = true
                    coroutineScope.launch(Dispatchers.IO) { EngineCore.executeZeroCopyInference(input) }
                }
            },
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00F2FF), disabledContainerColor = Color.DarkGray),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(if (isRunning) "PROCESSING..." else "EXECUTE", color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}
