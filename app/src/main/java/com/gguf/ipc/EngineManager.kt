package com.gguf.ipc

import android.content.Context
import org.json.JSONObject

/**
 * EngineManager — Manages all inference engines and selects the best one.
 * Supports llama.cpp (GGUF), MNN (.mnn), LiteRT-LM (.tflite/.litertlm).
 */
object EngineManager {

    private var currentEngine: InferenceEngine? = null
    private val engines = mutableMapOf<InferenceEngine.EngineType, InferenceEngine>()
    private var deviceInfo: DeviceUtils.DeviceInfo? = null

    fun init(context: Context) {
        engines[InferenceEngine.EngineType.LLAMA_CPP] = LlamaCppEngine()
        engines[InferenceEngine.EngineType.MNN] = MnnEngine()
        engines[InferenceEngine.EngineType.LITER_T] = LiteRtEngine()

        deviceInfo = DeviceUtils.detectDevice(context)
        SettingsManager.init(context)
        ChatManager.init(context)

        if (SettingsManager.autoDetectDevice && deviceInfo != null) {
            SettingsManager.applyToDeviceDefaults(deviceInfo!!)
        }
    }

    fun getDeviceInfo(): DeviceUtils.DeviceInfo? = deviceInfo

    fun getEngineForFormat(filePath: String): InferenceEngine {
        return when {
            filePath.endsWith(".gguf", ignoreCase = true) -> engines[InferenceEngine.EngineType.LLAMA_CPP]!!
            filePath.endsWith(".mnn", ignoreCase = true) -> engines[InferenceEngine.EngineType.MNN]!!
            filePath.endsWith(".tflite", ignoreCase = true) ||
            filePath.endsWith(".litertlm", ignoreCase = true) -> engines[InferenceEngine.EngineType.LITER_T]!!
            else -> engines[InferenceEngine.EngineType.LLAMA_CPP]!!
        }
    }

    fun getRecommendedEngine(): InferenceEngine.EngineType {
        if (deviceInfo == null) return InferenceEngine.EngineType.LLAMA_CPP
        return DeviceUtils.suggestEngine(deviceInfo!!)
    }

    fun loadModel(filePath: String): Boolean {
        val engine = getEngineForFormat(filePath)
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

    fun getEngine(type: InferenceEngine.EngineType): InferenceEngine? = engines[type]

    fun getSupportedExtensions(): Set<String> = setOf("gguf", "mnn", "tflite", "litertlm")

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
                    put("gpu_backends", "CPU (optimized)")
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
                }
            })
        }
    }
}
