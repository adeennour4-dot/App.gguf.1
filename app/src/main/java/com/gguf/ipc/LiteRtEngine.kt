package com.gguf.ipc

import android.util.Log
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * LiteRtEngine — InferenceEngine implementation using Google LiteRT-LM.
 * Supports .tflite and .litertlm models with CPU, GPU, and NPU backends.
 *
 * Uses Java reflection to access LiteRT-LM Kotlin API.
 * License: Apache 2.0 (allows commercial use)
 */
class LiteRtEngine : InferenceEngine {

    override val engineType = InferenceEngine.EngineType.LITER_T
    override val engineName = "LiteRT-LM"
    override var isModelLoaded = false
        private set

    private var currentModelPath = ""
    private var rawEngine: Any? = null
    private var rawConversation: Any? = null

    private val streamBuffer = StringBuilder()
    private val fullResponse = StringBuilder()
    private val inferenceDone = AtomicBoolean(false)
    private val tokensGenerated = AtomicInteger(0)
    private var systemPrompt: String = ""

    companion object {
        private const val TAG = "LiteRtEngine"

        private var engineClass: Class<*>? = null
        private var engineConfigClass: Class<*>? = null
        private var backendCpuClass: Class<*>? = null
        private var conversationClass: Class<*>? = null
        private var messageCallbackClass: Class<*>? = null

        init {
            try {
                // Pre-load native library before any reflection access
                try {
                    System.loadLibrary("litert-lm-native")
                    Log.i(TAG, "Native library loaded: litert-lm-native")
                } catch (e: UnsatisfiedLinkError) {
                    Log.w(TAG, "Native library not found (may load via AAR): ${e.message}")
                }

                engineClass = Class.forName("com.google.ai.edge.litertlm.Engine")
                engineConfigClass = Class.forName("com.google.ai.edge.litertlm.EngineConfig")
                conversationClass = Class.forName("com.google.ai.edge.litertlm.Conversation")
                messageCallbackClass = Class.forName("com.google.ai.edge.litertlm.MessageCallback")

                backendCpuClass = try {
                    Class.forName("com.google.ai.edge.litertlm.Backend\$CPU")
                } catch (e: ClassNotFoundException) {
                    Class.forName("com.google.ai.edge.litertlm.Backend$CPU")
                }

                // Enable speculative decoding (required for Gemma 4 models)
                try {
                    val expFlagsCls = Class.forName("com.google.ai.edge.litertlm.ExperimentalFlags")
                    val field = expFlagsCls.getField("enableSpeculativeDecoding")
                    field.setBoolean(null, true)
                    Log.i(TAG, "Speculative decoding enabled for Gemma 4 support")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not enable speculative decoding: ${e.message}")
                }

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
            val bckCpuCls = backendCpuClass ?: return false

            val cpuBackend = bckCpuCls.getConstructor(Integer::class.java).newInstance(null)

            val engineConfigCtor = cfgCls.constructors.first()
            val config = engineConfigCtor.newInstance(
                path,       // modelPath
                cpuBackend, // backend
                null,       // visionBackend
                null,       // audioBackend
                null,       // maxNumTokens
                null,       // maxNumImages
                null        // cacheDir
            )

            val eng = engCls.getConstructor(cfgCls).newInstance(config)

            engCls.getMethod("initialize").invoke(eng)

            rawEngine = eng
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
            rawConversation?.let { conv ->
                conversationClass?.getMethod("close")?.invoke(conv)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error closing conversation: ${e.message}")
        }
        try {
            rawEngine?.let { eng ->
                engineClass?.getMethod("close")?.invoke(eng)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error closing engine: ${e.message}")
        }
        rawEngine = null
        rawConversation = null
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
        val eng = rawEngine ?: return
        val engCls = engineClass ?: return
        val convCls = conversationClass ?: return
        val msgCbCls = messageCallbackClass ?: return

        synchronized(streamBuffer) { streamBuffer.clear() }
        fullResponse.clear()
        inferenceDone.set(false)
        tokensGenerated.set(0)

        try {
            if (rawConversation == null) {
                // createConversation has a ConversationConfig parameter with default
                val conv = try {
                    engCls.getMethod("createConversation").invoke(eng)
                } catch (e: NoSuchMethodException) {
                    val convConfigCls = Class.forName("com.google.ai.edge.litertlm.ConversationConfig")
                    val defaultConfig = convConfigCls.getDeclaredConstructor().newInstance()
                    engCls.getMethod("createConversation", convConfigCls)
                        .invoke(eng, defaultConfig)
                }
                rawConversation = conv
                if (systemPrompt.isNotEmpty()) {
                    try {
                        val msgCls = Class.forName("com.google.ai.edge.litertlm.Message")
                        val contentsCls = Class.forName("com.google.ai.edge.litertlm.Contents")
                        // Contents.of(systemPrompt) via companion object
                        val companionField = contentsCls.getDeclaredField("Companion")
                        companionField.isAccessible = true
                        val contentsCompanion = companionField.get(null)
                        val systemContents = contentsCompanion.javaClass
                            .getMethod("of", String::class.java)
                            .invoke(contentsCompanion, systemPrompt)
                        // Message.system(contents) via companion object
                        val msgCompanionField = msgCls.getDeclaredField("Companion")
                        msgCompanionField.isAccessible = true
                        val msgCompanion = msgCompanionField.get(null)
                        val systemMessage = msgCompanion.javaClass
                            .getMethod("system", contentsCls)
                            .invoke(msgCompanion, systemContents)
                        // sendMessage has (Message, Map) — Map has default
                        convCls.getMethod("sendMessage", msgCls, Map::class.java)
                            .invoke(conv, systemMessage, emptyMap<String, Any>())
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to set system prompt: ${e.message}")
                    }
                }
            }

            val conv = rawConversation!!

            val callback = java.lang.reflect.Proxy.newProxyInstance(
                msgCbCls.classLoader,
                arrayOf(msgCbCls)
            ) { _, method, args ->
                when (method.name) {
                    "onMessage" -> {
                        val msg = args?.getOrNull(0)
                        if (msg != null) {
                            try {
                                val text = msg.javaClass.getMethod("toString")
                                    .invoke(msg) as? String ?: ""
                                synchronized(streamBuffer) {
                                    streamBuffer.clear()
                                    streamBuffer.append(text)
                                }
                                fullResponse.append(text)
                                tokensGenerated.incrementAndGet()
                            } catch (e: Exception) {
                                Log.w(TAG, "Error reading message: ${e.message}")
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

            convCls.getMethod("sendMessageAsync", String::class.java, msgCbCls, Map::class.java)
                .invoke(conv, prompt, callback, emptyMap<String, Any>())

        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute inference: ${e.message}", e)
            inferenceDone.set(true)
        }
    }

    override fun abortInference() {
        inferenceDone.set(true)
        try {
            conversationClass?.getMethod("cancelProcess")?.invoke(rawConversation)
        } catch (e: Exception) {
            Log.w(TAG, "Error canceling: ${e.message}")
        }
    }

    override fun readPartialStream(): String {
        return synchronized(streamBuffer) { streamBuffer.toString() }
    }

    override fun readTokenStream(): String {
        return fullResponse.toString()
    }

    override fun isInferenceDone(): Boolean = inferenceDone.get()

    override fun getTokensGenerated(): Int = tokensGenerated.get()

    override fun getKvCacheUsage(): Int = 0

    override fun resetContext() {
        try {
            rawConversation?.let { conv ->
                conversationClass?.getMethod("close")?.invoke(conv)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error closing conversation on reset: ${e.message}")
        }
        rawConversation = null
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
