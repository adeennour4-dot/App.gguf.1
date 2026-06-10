package com.gguf.ipc

import android.content.Context
import org.json.JSONObject

/**
 * EngineManager — Manages all inference engines and selects the best one.
 * Supports automatic engine selection based on file format and device capabilities.
 */
object EngineManager {

    private var currentEngine: InferenceEngine? = null
    private val engines = mutableMapOf<InferenceEngine.EngineType, InferenceEngine>()
    private var deviceInfo: DeviceUtils.DeviceInfo? = null

    fun init(context: Context) {
        // Initialize all engines
        engines[InferenceEngine.EngineType.LLAMA_CPP] = LlamaCppEngine()
        engines[InferenceEngine.EngineType.MNN] = MnnEngine()
        engines[InferenceEngine.EngineType.LITER_T] = LiteRtEngine()

        // Detect device
        deviceInfo = DeviceUtils.detectDevice(context)

        // Initialize managers
        SettingsManager.init(context)
        ChatManager.init(context)

        // Apply device-specific defaults if auto-detect is enabled
        if (SettingsManager.autoDetectDevice && deviceInfo != null) {
            SettingsManager.applyToDeviceDefaults(deviceInfo!!)
        }
    }

    fun getDeviceInfo(): DeviceUtils.DeviceInfo? = deviceInfo

    /**
     * Get the best engine for a given file format.
     */
    fun getEngineForFormat(filePath: String): InferenceEngine {
        return when {
            filePath.endsWith(".gguf", ignoreCase = true) -> engines[InferenceEngine.EngineType.LLAMA_CPP]!!
            filePath.endsWith(".mnn", ignoreCase = true) -> engines[InferenceEngine.EngineType.MNN]!!
            filePath.endsWith(".tflite", ignoreCase = true) ||
            filePath.endsWith(".litertlm", ignoreCase = true) -> engines[InferenceEngine.EngineType.LITER_T]!!
            else -> engines[InferenceEngine.EngineType.LLAMA_CPP]!!  // Default to llama.cpp
        }
    }

    /**
     * Get the recommended engine based on device capabilities.
     */
    fun getRecommendedEngine(): InferenceEngine.EngineType {
        if (deviceInfo == null) return InferenceEngine.EngineType.LLAMA_CPP
        return DeviceUtils.suggestEngine(deviceInfo!!)
    }

    /**
     * Load a model with the appropriate engine.
     */
    fun loadModel(filePath: String): Boolean {
        val engine = getEngineForFormat(filePath)

        // Check RAM before loading
        val context = null  // Would need context parameter
        // if (context != null && !DeviceUtils.canFitModel(context, estimatedSizeGB)) {
        //     return false  // Not enough RAM
        // }

        // Apply settings
        engine.setConfig(SettingsManager.toConfig())
        engine.setRepeatPenalty(SettingsManager.toRepeatPenaltyConfig())
        engine.setSystemPrompt(SettingsManager.systemPrompt)

        val success = engine.loadModel(filePath)
        if (success) {
            currentEngine = engine
        }
        return success
    }

    fun getCurrentEngine(): InferenceEngine? = currentEngine

    fun getEngine(type: InferenceEngine.EngineType): InferenceEngine? {
        return engines[type]
    }

    /**
     * Get supported file extensions for all engines.
     */
    fun getSupportedExtensions(): Set<String> {
        return setOf("gguf", "mnn", "tflite", "litertlm")
    }

    /**
     * Get engine info as JSON.
     */
    fun getEngineInfo(): JSONObject {
        val current = currentEngine
        return JSONObject().apply {
            put("current_engine", current?.engineName ?: "none")
            put("current_type", current?.engineType?.name ?: "none")
            put("model_loaded", current?.isModelLoaded ?: false)
            put("supported_engines", JSONObject().apply {
                put("llama_cpp", JSONObject().apply {
                    put("formats", "gguf")
                    put("gpu_backends", "Vulkan, OpenCL, CPU")
                    put("license", "MIT")
                })
                put("mnn", JSONObject().apply {
                    put("formats", "mnn")
                    put("gpu_backends", "OpenCL, CPU")
                    put("license", "Apache 2.0")
                })
                put("litert_lm", JSONObject().apply {
                    put("formats", "tflite, litertlm")
                    put("gpu_backends", "GPU, NPU, CPU")
                    put("license", "Apache 2.0")
                })
            })
            put("device_info", JSONObject().apply {
                val info = deviceInfo
                if (info != null) {
                    put("soc", info.socModel)
                    put("cpu_cores", info.cpuCores)
                    put("total_ram_mb", info.totalRamMB)
                    put("is_snapdragon", info.isSnapdragon)
                    put("is_exynos", info.isExynos)
                    put("is_mediatek", info.isMediaTek)
                    put("is_tensor", info.isTensor)
                }
            })
        }
    }
}
