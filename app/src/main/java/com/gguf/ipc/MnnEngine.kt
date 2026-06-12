package com.gguf.ipc

import org.json.JSONObject
import java.io.File

/**
 * MnnEngine — InferenceEngine implementation using Alibaba MNN-LLM.
 * Supports .mnn models with CPU (8.6x faster than llama.cpp) and OpenCL GPU backends.
 *
 * Uses MNN-LLM C API via JNI bridge.
 * License: Apache 2.0 (allows commercial use)
 */
class MnnEngine : InferenceEngine {

    override val engineType = InferenceEngine.EngineType.MNN
    override val engineName = "MNN"
    override var isModelLoaded = false
        private set

    private var currentModelPath = ""

    companion object {
        init {
            try {
                System.loadLibrary("mnn-bridge")
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e("MnnEngine", "MNN native library not available: ${e.message}")
            }
        }
    }

    // Native JNI methods (implemented in mnn-bridge.cpp)
    private external fun mnnLoadModel(path: String): Boolean
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
        return try {
            val modelDir = findModelDirectory(path)
            isModelLoaded = mnnLoadModel(modelDir)
            isModelLoaded
        } catch (e: Exception) {
            android.util.Log.e("MnnEngine", "Failed to load MNN model: ${e.message}")
            false
        }
    }

    /**
     * MNN-LLM expects a directory containing config.json and model files.
     * If a single .mnn file is provided, find its parent directory.
     */
    private fun findModelDirectory(path: String): String {
        val file = File(path)
        if (file.isDirectory) {
            // Check if config.json exists
            if (File(file, "config.json").exists()) return path
        }
        // If it's a file, use its parent directory
        val parent = file.parentFile
        if (parent != null && File(parent, "config.json").exists()) {
            return parent.absolutePath
        }
        // Fallback: return original path
        return path
    }

    override fun unloadModel() {
        try {
            mnnResetContext()
        } catch (_: Exception) {}
        isModelLoaded = false
        currentModelPath = ""
    }

    override fun setConfig(config: InferenceEngine.Config) {
        android.util.Log.i("MnnEngine", "Config: ctx=${config.nCtx}, maxTokens=${config.maxNewTokens}")
        // TODO: Apply config to MNN engine via JNI
    }

    override fun setRepeatPenalty(config: InferenceEngine.RepeatPenaltyConfig) {
        android.util.Log.i("MnnEngine", "Repeat penalty: ${config.repeatPenalty}")
        // TODO: Apply repeat penalty via JNI
    }

    override fun setSystemPrompt(prompt: String) {
        android.util.Log.i("MnnEngine", "System prompt set")
        // TODO: Apply system prompt via JNI
    }

    override fun executeInference(prompt: String) {
        try {
            mnnExecuteInference(prompt)
        } catch (e: Exception) {
            android.util.Log.e("MnnEngine", "Inference failed: ${e.message}")
        }
    }

    override fun abortInference() {
        try {
            mnnAbortInference()
        } catch (e: Exception) {
            android.util.Log.e("MnnEngine", "Abort failed: ${e.message}")
        }
    }

    override fun readPartialStream(): String {
        return try {
            mnnReadPartialStream()
        } catch (e: Exception) {
            ""
        }
    }

    override fun readTokenStream(): String {
        return try {
            mnnReadTokenStream()
        } catch (e: Exception) {
            ""
        }
    }

    override fun isInferenceDone(): Boolean {
        return try {
            mnnIsInferenceDone()
        } catch (e: Exception) {
            true
        }
    }

    override fun getTokensGenerated(): Int {
        return try {
            mnnGetTokensGenerated()
        } catch (e: Exception) {
            0
        }
    }

    override fun getKvCacheUsage(): Int {
        return try {
            mnnGetKvCacheUsage()
        } catch (e: Exception) {
            0
        }
    }

    override fun resetContext() {
        try {
            mnnResetContext()
        } catch (e: Exception) {
            android.util.Log.e("MnnEngine", "Reset failed: ${e.message}")
        }
    }

    override fun getModelInfo(): JSONObject {
        return try {
            JSONObject(mnnGetModelInfo())
        } catch (e: Exception) {
            JSONObject().put("error", "Failed to get MNN model info")
        }
    }

    override fun benchmark(ppTokens: Int, tgTokens: Int): JSONObject {
        return try {
            JSONObject(mnnBenchmark(ppTokens, tgTokens))
        } catch (e: Exception) {
            JSONObject().put("error", "MNN benchmark failed")
        }
    }

    override fun exportChatHistory(): String {
        return try {
            mnnExportChatHistory()
        } catch (e: Exception) {
            "MNN chat export not available"
        }
    }

    override fun supportsFormat(filePath: String): Boolean {
        return filePath.endsWith(".mnn", ignoreCase = true)
    }
}
