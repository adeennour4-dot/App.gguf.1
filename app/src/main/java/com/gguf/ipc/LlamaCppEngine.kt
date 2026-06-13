package com.gguf.ipc

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LlamaCppEngine : InferenceEngine {

    override val engineType = InferenceEngine.EngineType.LLAMA_CPP
    override val engineName = "llama.cpp"
    override var isModelLoaded = false
        private set

    private var currentModelPath = ""
    private var kvCacheUsage = 0

    companion object {
        private const val TAG = "LlamaCppEngine"
    }

    // Callback-managed state - updated from C++ JNI callbacks
    private val partialStream = StringBuilder()
    private val fullResponse = StringBuilder()
    private val inferenceDone = AtomicBoolean(true)
    private val tokensGenerated = AtomicInteger(0)

    override fun loadModel(path: String): Boolean {
        currentModelPath = path
        val success = EngineCore.loadModel(path)
        isModelLoaded = success
        if (success) {
            // Apply current config after model load
            setConfig(SettingsManager.toConfig())
            setRepeatPenalty(SettingsManager.toRepeatPenaltyConfig())
            setSystemPrompt(SettingsManager.systemPrompt)
        }
        return success
    }

    override fun unloadModel() {
        EngineCore.resetContextNative()
        isModelLoaded = false
        currentModelPath = ""
        partialStream.clear()
        fullResponse.clear()
        inferenceDone.set(true)
        tokensGenerated.set(0)
        kvCacheUsage = 0
    }

    override fun setConfig(config: InferenceEngine.Config) {
        EngineCore.setEngineConfig(EngineCore.Config(
            nCtx = config.nCtx,
            nBatch = config.nBatch,
            maxNewTokens = config.maxNewTokens,
            temperature = config.temperature,
            topP = config.topP,
            minP = config.minP,
            nGpuLayers = config.nGpuLayers,
            nThreads = config.nThreads,
            seed = config.seed
        ))
    }

    override fun setRepeatPenalty(config: InferenceEngine.RepeatPenaltyConfig) {
        EngineCore.setRepeatPenalty(EngineCore.RepeatPenaltyConfig(
            repeatPenalty = config.repeatPenalty,
            freqPenalty = config.freqPenalty,
            presPenalty = config.presPenalty
        ))
    }

    override fun setSystemPrompt(prompt: String) {
        EngineCore.setSystemPromptNative(prompt)
    }

    override fun executeInference(prompt: String) {
        partialStream.clear()
        fullResponse.clear()
        inferenceDone.set(false)
        tokensGenerated.set(0)
        kvCacheUsage = 0

        val cb = object : EngineCore.TokenCallback {
            override fun onToken(token: String) {
                partialStream.append(token)
                fullResponse.append(token)
            }
            override fun onDone() {
                inferenceDone.set(true)
            }
            override fun onError(error: String) {
                android.util.Log.e(TAG, "Inference error: $error")
                partialStream.append("\n[Error: $error]")
                inferenceDone.set(true)
            }
            override fun onKvCacheUsage(percent: Int) {
                kvCacheUsage = percent
            }
            override fun onTokensGenerated(count: Int) {
                tokensGenerated.set(count)
            }
        }

        EngineCore.executeWithCallbackNative(prompt, cb)
    }

    override fun abortInference() {
        EngineCore.abortInferenceNative()
    }

    override fun readPartialStream(): String {
        return synchronized(partialStream) {
            val s = partialStream.toString()
            partialStream.clear()
            s
        }
    }

    override fun readTokenStream(): String {
        return fullResponse.toString()
    }

    override fun isInferenceDone(): Boolean = inferenceDone.get()

    override fun getTokensGenerated(): Int = tokensGenerated.get()

    override fun getKvCacheUsage(): Int = kvCacheUsage

    override fun resetContext() {
        EngineCore.resetContextNative()
        partialStream.clear()
        fullResponse.clear()
        inferenceDone.set(true)
        tokensGenerated.set(0)
        kvCacheUsage = 0
    }

    override fun getModelInfo(): JSONObject {
        return try { JSONObject(EngineCore.getModelInfoNative()) }
        catch (e: Exception) { JSONObject().put("error", "Failed to get model info") }
    }

    override fun benchmark(ppTokens: Int, tgTokens: Int): JSONObject {
        return try { JSONObject(EngineCore.benchmarkNative(ppTokens, tgTokens)) }
        catch (e: Exception) { JSONObject().put("error", "Benchmark failed") }
    }

    override fun exportChatHistory(): String {
        return try { EngineCore.exportChatHistoryNative() }
        catch (e: Exception) { "Export failed" }
    }

    override fun supportsFormat(filePath: String): Boolean {
        return filePath.endsWith(".gguf", ignoreCase = true)
    }
}
