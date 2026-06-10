package com.gguf.ipc

import android.util.Log

/**
 * EngineCore v6 — JNI bridge to llama.cpp.
 *
 * v6 removes shared memory ring buffer in favour of JNI callbacks
 * (push-based streaming per token, no polling overhead).
 */
object EngineCore {

    private const val TAG = "GGUF_ZeroCopy_v6"

    init { System.loadLibrary("ipc-bridge") }

    // -----------------------------------------------------------------------
    // Callback interface (called from C++ via JNI)
    // -----------------------------------------------------------------------
    interface TokenCallback {
        fun onToken(token: String)
        fun onDone()
        fun onError(error: String)
        fun onKvCacheUsage(percent: Int)
        fun onTokensGenerated(count: Int)
    }

    // -----------------------------------------------------------------------
    // Native declarations
    // -----------------------------------------------------------------------
    external fun loadGgufModelNative(filePath: String): Boolean
    external fun executeWithCallbackNative(prompt: String, callback: TokenCallback)
    external fun abortInferenceNative()

    external fun setEngineConfigNative(
        nCtx: Int, nBatch: Int, maxNewTokens: Int, temperature: Float,
        topP: Float, minP: Float, nGpuLayers: Int, nThreads: Int, seed: Int
    )
    external fun setSystemPromptNative(prompt: String)
    external fun resetContextNative()
    external fun getModelInfoNative(): String
    external fun benchmarkNative(ppTokens: Int, tgTokens: Int): String
    external fun setRepeatPenaltyNative(repeatPenalty: Float, freqPenalty: Float, presPenalty: Float)
    external fun exportChatHistoryNative(): String
    external fun getKvCacheUsageNative(): Int

    // -----------------------------------------------------------------------
    // Config
    // -----------------------------------------------------------------------
    data class Config(
        val nCtx: Int          = 8192,
        val nBatch: Int        = 2048,
        val maxNewTokens: Int  = 4096,
        val temperature: Float = 0.7f,
        val topP: Float        = 0.9f,
        val minP: Float        = 0.05f,
        val nGpuLayers: Int    = 99,
        val nThreads: Int      = 0,  // 0 = auto (all cores)
        val seed: Int          = -1
    )

    data class RepeatPenaltyConfig(
        val repeatPenalty: Float = 1.1f,
        val freqPenalty: Float   = 0.0f,
        val presPenalty: Float   = 0.0f
    )

    fun setEngineConfig(cfg: Config) {
        setEngineConfigNative(
            cfg.nCtx, cfg.nBatch, cfg.maxNewTokens,
            cfg.temperature, cfg.topP, cfg.minP,
            cfg.nGpuLayers, cfg.nThreads, cfg.seed
        )
    }

    fun setRepeatPenalty(cfg: RepeatPenaltyConfig) {
        setRepeatPenaltyNative(cfg.repeatPenalty, cfg.freqPenalty, cfg.presPenalty)
    }

    fun loadModel(path: String): Boolean {
        Log.i(TAG, "Loading model: $path")
        return loadGgufModelNative(path)
    }
}
