package com.gguf.ipc

import org.json.JSONObject

/**
 * InferenceEngine — Abstract interface for all inference backends.
 * Each engine (llama.cpp, MNN, LiteRT-LM) implements this interface.
 */
interface InferenceEngine {

    enum class EngineType { LLAMA_CPP, MNN, LITER_T }

    data class Config(
        val nCtx: Int = 8192,
        val nBatch: Int = 2048,
        val maxNewTokens: Int = 4096,
        val temperature: Float = 0.7f,
        val topP: Float = 0.9f,
        val minP: Float = 0.05f,
        val nGpuLayers: Int = 99,
        val nThreads: Int = 4,
        val seed: Int = -1
    )

    data class RepeatPenaltyConfig(
        val repeatPenalty: Float = 1.1f,
        val freqPenalty: Float = 0.0f,
        val presPenalty: Float = 0.0f
    )

    /** Engine type identifier */
    val engineType: EngineType

    /** Human-readable engine name */
    val engineName: String

    /** Whether a model is currently loaded */
    val isModelLoaded: Boolean

    /** Load a model from file path */
    fun loadModel(path: String): Boolean

    /** Unload the current model */
    fun unloadModel()

    /** Apply inference configuration */
    fun setConfig(config: Config)

    /** Set repeat penalty parameters */
    fun setRepeatPenalty(config: RepeatPenaltyConfig)

    /** Set system prompt */
    fun setSystemPrompt(prompt: String)

    /** Execute inference (blocking, call from coroutine) */
    fun executeInference(prompt: String)

    /** Abort current inference */
    fun abortInference()

    /** Read partial stream (for polling) */
    fun readPartialStream(): String

    /** Read complete token stream */
    fun readTokenStream(): String

    /** Check if inference is done */
    fun isInferenceDone(): Boolean

    /** Get tokens generated so far */
    fun getTokensGenerated(): Int

    /** Get KV cache usage percentage */
    fun getKvCacheUsage(): Int

    /** Reset context (clear history and KV cache) */
    fun resetContext()

    /** Get model metadata as JSON */
    fun getModelInfo(): JSONObject

    /** Run benchmark (ppTokens, tgTokens) */
    fun benchmark(ppTokens: Int, tgTokens: Int): JSONObject

    /** Export chat history */
    fun exportChatHistory(): String

    /** Check if file format is supported by this engine */
    fun supportsFormat(filePath: String): Boolean
}
