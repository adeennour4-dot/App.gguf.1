package com.gguf.ipc

import org.json.JSONObject

/**
 * LlamaCppEngine — InferenceEngine implementation using llama.cpp via JNI.
 * Supports GGUF models with Vulkan (Mali/Xclipse) or OpenCL (Adreno) GPU backends.
 */
class LlamaCppEngine : InferenceEngine {

    override val engineType = InferenceEngine.EngineType.LLAMA_CPP
    override val engineName = "llama.cpp"
    override var isModelLoaded = false
        private set

    private var currentModelPath = ""

    override fun loadModel(path: String): Boolean {
        currentModelPath = path
        val success = EngineCore.loadModel(path)
        isModelLoaded = success
        return success
    }

    override fun unloadModel() {
        EngineCore.resetContextNative()
        isModelLoaded = false
        currentModelPath = ""
    }

    override fun setConfig(config: InferenceEngine.Config) {
        EngineCore.setEngineConfig(EngineCore.Config(
            nCtx = config.nCtx,
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
        EngineCore.executeZeroCopyInference(prompt)
    }

    override fun abortInference() {
        EngineCore.abortInferenceNative()
    }

    override fun readPartialStream(): String {
        return EngineCore.readPartialStream()
    }

    override fun readTokenStream(): String {
        return EngineCore.readTokenStream()
    }

    override fun isInferenceDone(): Boolean {
        return EngineCore.isInferenceDone()
    }

    override fun getTokensGenerated(): Int {
        return EngineCore.getTokensGenerated()
    }

    override fun getKvCacheUsage(): Int {
        return EngineCore.getKvCacheUsageNative()
    }

    override fun resetContext() {
        EngineCore.resetContextNative()
    }

    override fun getModelInfo(): JSONObject {
        return try {
            JSONObject(EngineCore.getModelInfoNative())
        } catch (e: Exception) {
            JSONObject().put("error", "Failed to get model info")
        }
    }

    override fun benchmark(ppTokens: Int, tgTokens: Int): JSONObject {
        return try {
            JSONObject(EngineCore.benchmarkNative(ppTokens, tgTokens))
        } catch (e: Exception) {
            JSONObject().put("error", "Benchmark failed")
        }
    }

    override fun exportChatHistory(): String {
        return EngineCore.exportChatHistoryNative()
    }

    override fun supportsFormat(filePath: String): Boolean {
        return filePath.endsWith(".gguf", ignoreCase = true)
    }
}
