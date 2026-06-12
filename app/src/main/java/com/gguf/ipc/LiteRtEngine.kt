package com.gguf.ipc

import android.util.Log
import com.google.ai.edge.litertlm.*
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LiteRtEngine : InferenceEngine {

    override val engineType = InferenceEngine.EngineType.LITER_T
    override val engineName = "LiteRT-LM"
    override var isModelLoaded = false
        private set

    private var currentModelPath = ""
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    private val partialStream = StringBuilder()
    private val fullResponse = StringBuilder()
    private val inferenceDone = AtomicBoolean(false)
    private val tokensGenerated = AtomicInteger(0)
    private var systemPrompt: String = ""
    private var preferredBackend: Backend = Backend.CPU(null)

    companion object {
        private const val TAG = "LiteRtEngine"

        init {
            try {
                System.loadLibrary("litert-lm-native")
                Log.i(TAG, "Native library loaded: litert-lm-native")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Native library not found (may load via AAR): ${e.message}")
            }
        }
    }

    init {
        // Initialize preferred backend (instance initialization)
        preferredBackend = Backend.CPU(null)
        Log.i(TAG, "Using CPU backend")
    }

    override fun loadModel(path: String): Boolean {
        currentModelPath = path
        return try {
            // Check if it's a .litertlm file (bundle) or .tflite
            val isLitertlm = path.endsWith(".litertlm", ignoreCase = true)
            
            val config = if (isLitertlm) {
                // For .litertlm bundles, use the bundle path directly
                EngineConfig(
                    path,
                    preferredBackend,
                    null,  // modelLoader
                    null,  // tokenizer
                    null,  // speculative decoding config
                    null,  // generation config
                    null   // options
                )
            } else {
                // For .tflite files, try to load with tokenizer
                EngineConfig(
                    path,
                    preferredBackend,
                    null,
                    null,
                    null,
                    null,
                    null
                )
            }
            engine = Engine(config)
            engine!!.initialize()
            isModelLoaded = true
            Log.i(TAG, "LiteRT-LM model loaded: $path")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model: ${e.message}", e)
            // Try fallback: load as raw TFLite
            tryLoadAsTflite(path)
        }
    }

    private fun tryLoadAsTflite(path: String): Boolean {
        return try {
            Log.i(TAG, "Trying fallback TFLite load...")
            val config = EngineConfig(
                path,
                preferredBackend,
                null,
                null,
                null,
                null,
                null
            )
            engine = Engine(config)
            engine!!.initialize()
            isModelLoaded = true
            Log.i(TAG, "LiteRT-LM fallback load succeeded: $path")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Fallback load also failed: ${e.message}", e)
            false
        }
    }

    override fun unloadModel() {
        try {
            conversation?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing conversation: ${e.message}")
        }
        try {
            engine?.close()
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

    fun setBackend(backend: Backend) {
        preferredBackend = backend
        Log.i(TAG, "Backend set to: $backend")
    }

    override fun setSystemPrompt(prompt: String) {
        systemPrompt = prompt
        Log.i(TAG, "System prompt set")
    }

    override fun executeInference(prompt: String) {
        synchronized(partialStream) {
            partialStream.clear()
            fullResponse.clear()
        }
        inferenceDone.set(false)
        tokensGenerated.set(0)

        try {
            if (conversation == null) {
                conversation = engine?.createConversation()
                if (systemPrompt.isNotEmpty()) {
                    try {
                        val contents = Contents.of(systemPrompt)
                        val msg = Message.system(contents)
                        conversation?.sendMessage(msg, emptyMap<String, Any>())
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to set system prompt: ${e.message}")
                    }
                }
            }

            val callback = object : MessageCallback {
                override fun onMessage(message: Message) {
                    val text = message.toString()
                    synchronized(partialStream) {
                        partialStream.clear()
                        partialStream.append(text)
                        fullResponse.append(text)
                    }
                    tokensGenerated.incrementAndGet()
                }

                override fun onDone() {
                    inferenceDone.set(true)
                    Log.i(TAG, "Inference done, tokens=${tokensGenerated.get()}")
                }

                override fun onError(throwable: Throwable) {
                    Log.e(TAG, "Inference error: ${throwable.message}")
                    inferenceDone.set(true)
                }
            }

            conversation?.sendMessageAsync(prompt, callback, emptyMap<String, Any>())

        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute inference: ${e.message}", e)
            inferenceDone.set(true)
        }
    }

    override fun abortInference() {
        inferenceDone.set(true)
        try {
            conversation?.cancelProcess()
        } catch (e: Exception) {
            Log.w(TAG, "Error canceling: ${e.message}")
        }
    }

    override fun readPartialStream(): String {
        return synchronized(partialStream) {
            val s = partialStream.toString()
            partialStream.clear()
            s
        }
    }

    override fun readTokenStream(): String {
        return synchronized(partialStream) { fullResponse.toString() }
    }

    override fun isInferenceDone(): Boolean = inferenceDone.get()

    override fun getTokensGenerated(): Int = tokensGenerated.get()

    override fun getKvCacheUsage(): Int = 0

    override fun resetContext() {
        try {
            conversation?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing conversation on reset: ${e.message}")
        }
        conversation = null
        synchronized(partialStream) { partialStream.clear(); fullResponse.clear() }
        inferenceDone.set(false)
        tokensGenerated.set(0)
    }

    override fun getModelInfo(): JSONObject {
        return JSONObject().apply {
            put("engine", "LiteRT-LM")
            put("model", currentModelPath)
            put("supported_formats", "tflite, litertlm")
            put("backends", "CPU, GPU, NPU")
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
