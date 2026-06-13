package com.gguf.ipc

import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * MnnEngine — InferenceEngine implementation using Alibaba MNN-LLM.
 * Supports .mnn models with CPU-optimized backend.
 */
class MnnEngine : InferenceEngine {

    override val engineType = InferenceEngine.EngineType.MNN
    override val engineName = "MNN"
    override var isModelLoaded = false
        private set

    private var currentModelPath = ""
    private var kvCacheUsage = 0

    companion object {
        private const val TAG = "MnnEngine"
        init {
            try {
                System.loadLibrary("mnn-bridge")
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e(TAG, "MNN native library not available: ${e.message}")
            }
        }
    }

    private val partialStream = StringBuilder()
    private val fullResponse = StringBuilder()
    private val inferenceDone = AtomicBoolean(true)
    private val tokensGenerated = AtomicInteger(0)

    // Native JNI methods
    private external fun mnnLoadModel(path: String): Boolean
    private external fun mnnUnloadModel()
    private external fun mnnSetConfig(nCtx: Int, nBatch: Int, maxTokens: Int, temperature: Float, topP: Float, minP: Float)
    private external fun mnnSetRepeatPenalty(repeatPenalty: Float, freqPenalty: Float, presPenalty: Float)
    private external fun mnnSetSystemPrompt(prompt: String)
    private external fun mnnExecuteInference(prompt: String)
    private external fun mnnAbortInference()
    private external fun mnnReadPartialStream(): String
    private external fun mnnReadTokenStream(): String
    private external fun mnnIsInferenceDone(): Boolean
    private external fun mnnGetTokensGenerated(): Int
    private external fun mnnGetKvCacheUsage(): Int
    private external fun mnnResetContext()
    private external fun mnnGetModelInfo(): String
    private external fun mnnBenchmark(ppTokens: Int, tgTokens: Int): String
    private external fun mnnExportChatHistory(): String

    override fun loadModel(path: String): Boolean {
        currentModelPath = path
        val modelDir = findModelDirectory(path)
        return try {
            val success = mnnLoadModel(modelDir)
            isModelLoaded = success
            if (success) android.util.Log.i(TAG, "MNN model loaded: $modelDir")
            else android.util.Log.e(TAG, "MNN model load failed: $modelDir")
            success
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to load MNN model: ${e.message}", e)
            false
        }
    }

    private fun findModelDirectory(path: String): String {
        val file = File(path)
        if (file.isDirectory) {
            if (File(file, "config.json").exists()) return path
        }
        val parent = file.parentFile
        if (parent != null && File(parent, "config.json").exists()) {
            return parent.absolutePath
        }
        return path
    }

    override fun unloadModel() {
        try { mnnUnloadModel() } catch (_: Exception) {}
        isModelLoaded = false
        currentModelPath = ""
        partialStream.clear()
        fullResponse.clear()
        inferenceDone.set(true)
        tokensGenerated.set(0)
    }

    override fun setConfig(config: InferenceEngine.Config) {
        try {
            mnnSetConfig(config.nCtx, config.nBatch, config.maxNewTokens, config.temperature, config.topP, config.minP)
        } catch (_: Exception) {}
    }

    override fun setRepeatPenalty(config: InferenceEngine.RepeatPenaltyConfig) {
        try { mnnSetRepeatPenalty(config.repeatPenalty, config.freqPenalty, config.presPenalty) } catch (_: Exception) {}
    }

    override fun setSystemPrompt(prompt: String) {
        try { mnnSetSystemPrompt(prompt) } catch (_: Exception) {}
    }

    override fun executeInference(prompt: String) {
        synchronized(partialStream) {
            partialStream.clear()
            fullResponse.clear()
        }
        inferenceDone.set(false)
        tokensGenerated.set(0)

        try {
            mnnExecuteInference(prompt)
            // MNN is synchronous - read result after completion
            val result = mnnReadTokenStream()
            synchronized(partialStream) {
                fullResponse.append(result)
                partialStream.append(result)
            }
            tokensGenerated.set(mnnGetTokensGenerated())
        } catch (e: Exception) {
            android.util.Log.e(TAG, "MNN inference error: ${e.message}", e)
        }
        inferenceDone.set(true)
    }

    override fun abortInference() {
        try { mnnAbortInference() } catch (_: Exception) {}
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

    override fun getKvCacheUsage(): Int = kvCacheUsage

    override fun resetContext() {
        try { mnnResetContext() } catch (_: Exception) {}
        synchronized(partialStream) {
            partialStream.clear()
            fullResponse.clear()
        }
        inferenceDone.set(true)
        tokensGenerated.set(0)
        kvCacheUsage = 0
    }

    override fun getModelInfo(): JSONObject {
        return try { JSONObject(mnnGetModelInfo()) }
        catch (e: Exception) { JSONObject().put("error", "Failed to get MNN model info") }
    }

    override fun benchmark(ppTokens: Int, tgTokens: Int): JSONObject {
        return try { JSONObject(mnnBenchmark(ppTokens, tgTokens)) }
        catch (e: Exception) { JSONObject().put("error", "MNN benchmark failed") }
    }

    override fun exportChatHistory(): String {
        return try { mnnExportChatHistory() }
        catch (e: Exception) { "MNN chat export not available" }
    }

    override fun supportsFormat(filePath: String): Boolean {
        return filePath.endsWith(".mnn", ignoreCase = true) ||
               filePath.endsWith(".tflite", ignoreCase = true) ||
               filePath.endsWith(".litertlm", ignoreCase = true)
    }
}
