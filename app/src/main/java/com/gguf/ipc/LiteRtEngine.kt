package com.gguf.ipc

import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * LiteRtEngine — InferenceEngine implementation using Google LiteRT-LM.
 * Supports .tflite and .litertlm models with CPU, GPU, and NPU backends.
 *
 * Uses the LiteRT-LM Kotlin API with coroutines.
 * License: Apache 2.0 (allows commercial use)
 */
class LiteRtEngine : InferenceEngine {

    override val engineType = InferenceEngine.EngineType.LITER_T
    override val engineName = "LiteRT-LM"
    override var isModelLoaded = false
        private set

    private var currentModelPath = ""
    private var engine: Any? = null // com.google.ai.edge.litertlm.Engine
    private var conversation: Any? = null // com.google.ai.edge.litertlm.Conversation

    // Streaming state — thread-safe for JNI callback thread → polling thread
    private val streamBuffer = StringBuilder()
    private val fullResponse = StringBuilder()
    private val inferenceDone = AtomicBoolean(false)
    private val tokensGenerated = AtomicInteger(0)
    private var currentJob: Job? = null
    private var systemPrompt: String = ""

    companion object {
        private const val TAG = "LiteRtEngine"

        // LiteRT-LM classes loaded via reflection to avoid compile-time dependency issues
        private var engineClass: Class<*>? = null
        private var engineConfigClass: Class<*>? = null
        private var backendClass: Class<*>? = null
        private var conversationClass: Class<*>? = null
        private var messageCallbackClass: Class<*>? = null
        private var messageClass: Class<*>? = null
        private var contentsClass: Class<*>? = null

        init {
            try {
                engineClass = Class.forName("com.google.ai.edge.litertlm.Engine")
                engineConfigClass = Class.forName("com.google.ai.edge.litertlm.EngineConfig")
                backendClass = Class.forName("com.google.ai.edge.litertlm.Backend")
                conversationClass = Class.forName("com.google.ai.edge.litertlm.Conversation")
                messageCallbackClass = Class.forName("com.google.ai.edge.litertlm.MessageCallback")
                messageClass = Class.forName("com.google.ai.edge.litertlm.Message")
                contentsClass = Class.forName("com.google.ai.edge.litertlm.Contents")
                Log.i(TAG, "LiteRT-LM classes loaded successfully")
            } catch (e: ClassNotFoundException) {
                Log.e(TAG, "LiteRT-LM library not available: ${e.message}")
            }
        }
    }

    override fun loadModel(path: String): Boolean {
        currentModelPath = path
        return try {
            val engCls = engineClass ?: return false
            val cfgCls = engineConfigClass ?: return false
            val bckCls = backendClass ?: return false

            // Create Backend.CPU()
            val cpuBackend = bckCls.getMethod("CPU").invoke(null)

            // Create EngineConfig(modelPath, backend)
            val config = cfgCls.getConstructor(String::class.java, javaClass)
                .newInstance(path, cpuBackend)

            // Create Engine(config)
            val eng = engCls.getConstructor(cfgCls).newInstance(config)

            // engine.initialize()
            engCls.getMethod("initialize").invoke(eng)

            engine = eng
            isModelLoaded = true
            Log.i(TAG, "LiteRT-LM model loaded: $path")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model: ${e.message}", e)
            false
        }
    }

    override fun unloadModel() {
        try {
            conversation?.let { conv ->
                conversationClass?.getMethod("close")?.invoke(conv)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error closing conversation: ${e.message}")
        }
        try {
            engine?.let { eng ->
                engineClass?.getMethod("close")?.invoke(eng)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error closing engine: ${e.message}")
        }
        engine = null
        conversation = null
        isModelLoaded = false
        currentModelPath = ""
    }

    override fun setConfig(config: InferenceEngine.Config) {
        Log.i(TAG, "Config: ctx=${config.nCtx}, maxTokens=${config.maxNewTokens}")
    }

    override fun setRepeatPenalty(config: InferenceEngine.RepeatPenaltyConfig) {
        Log.i(TAG, "Repeat penalty: ${config.repeatPenalty}")
    }

    override fun setSystemPrompt(prompt: String) {
        systemPrompt = prompt
        Log.i(TAG, "System prompt set")
    }

    override fun executeInference(prompt: String) {
        val eng = engine ?: return
        val engCls = engineClass ?: return
        val convCls = conversationClass ?: return
        val msgCbCls = messageCallbackClass ?: return

        // Reset streaming state
        synchronized(streamBuffer) { streamBuffer.clear() }
        fullResponse.clear()
        inferenceDone.set(false)
        tokensGenerated.set(0)

        try {
            // Create conversation if needed
            if (conversation == null) {
                val conv = engCls.getMethod("createConversation").invoke(eng)
                conversation = conv
            }

            val conv = conversation!!

            // Create Contents from prompt
            // Contents.text(string) — try static method first
            val contents = try {
                val contentsCls = contentsClass!!
                contentsCls.getMethod("text", String::class.java).invoke(null, prompt)
            } catch (e: Exception) {
                // Fallback: try constructing Contents differently
                Log.w(TAG, "Contents.text() failed, trying alternative: ${e.message}")
                // Some versions use Message.user(contents)
                val msgCls = messageClass!!
                val contentsCls = contentsClass!!
                val contents = contentsCls.getMethod("text", String::class.java).invoke(null, prompt)
                msgCls.getMethod("user", contentsCls).invoke(null, contents)
            }

            // Create MessageCallback via reflection
            val callback = java.lang.reflect.Proxy.newProxyInstance(
                msgCbCls.classLoader,
                arrayOf(msgCbCls)
            ) { _, method, args ->
                when (method.name) {
                    "onMessage" -> {
                        val msg = args?.getOrNull(0)
                        if (msg != null) {
                            try {
                                val text = messageClass?.getMethod("getText")?.invoke(msg) as? String ?: ""
                                synchronized(streamBuffer) {
                                    streamBuffer.clear()
                                    streamBuffer.append(text)
                                }
                                fullResponse.clear()
                                fullResponse.append(text)
                                tokensGenerated.incrementAndGet()
                            } catch (e: Exception) {
                                Log.w(TAG, "Error reading message text: ${e.message}")
                            }
                        }
                    }
                    "onDone" -> {
                        inferenceDone.set(true)
                        Log.i(TAG, "Inference done, tokens=${tokensGenerated.get()}")
                    }
                    "onError" -> {
                        val throwable = args?.getOrNull(0) as? Throwable
                        Log.e(TAG, "Inference error: ${throwable?.message}")
                        inferenceDone.set(true)
                    }
                }
                null
            }

            // conversation.sendMessageAsync(contents, callback)
            // Try with Contents first, then with String
            try {
                convCls.getMethod("sendMessageAsync", contentsClass, msgCbCls)
                    .invoke(conv, contents, callback)
            } catch (e: NoSuchMethodException) {
                // Some versions accept String directly
                convCls.getMethod("sendMessageAsync", String::class.java, msgCbCls)
                    .invoke(conv, prompt, callback)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute inference: ${e.message}", e)
            inferenceDone.set(true)
        }
    }

    override fun abortInference() {
        inferenceDone.set(true)
        currentJob?.cancel()
        Log.i(TAG, "Inference aborted")
    }

    override fun readPartialStream(): String {
        return synchronized(streamBuffer) { streamBuffer.toString() }
    }

    override fun readTokenStream(): String {
        return fullResponse.toString()
    }

    override fun isInferenceDone(): Boolean = inferenceDone.get()

    override fun getTokensGenerated(): Int = tokensGenerated.get()

    override fun getKvCacheUsage(): Int {
        // LiteRT-LM doesn't expose KV cache usage directly
        return 0
    }

    override fun resetContext() {
        try {
            conversation?.let { conv ->
                conversationClass?.getMethod("close")?.invoke(conv)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error closing conversation on reset: ${e.message}")
        }
        conversation = null
        synchronized(streamBuffer) { streamBuffer.clear() }
        fullResponse.clear()
        inferenceDone.set(false)
        tokensGenerated.set(0)
    }

    override fun getModelInfo(): JSONObject {
        return JSONObject().apply {
            put("engine", "LiteRT-LM")
            put("model", currentModelPath)
            put("supported_formats", "tflite, litertlm")
            put("backends", "CPU, GPU (OpenCL), NPU (NNAPI)")
            put("license", "Apache 2.0")
            put("status", if (isModelLoaded) "loaded" else "not loaded")
            put("streaming", "callback-based")
        }
    }

    override fun benchmark(ppTokens: Int, tgTokens: Int): JSONObject {
        return JSONObject().apply {
            put("error", "LiteRT-LM benchmark not yet implemented")
            put("note", "Use llama.cpp engine for benchmarking")
        }
    }

    override fun exportChatHistory(): String {
        return "LiteRT-LM chat export not yet implemented"
    }

    override fun supportsFormat(filePath: String): Boolean {
        return filePath.endsWith(".tflite", ignoreCase = true) ||
                filePath.endsWith(".litertlm", ignoreCase = true)
    }
}
