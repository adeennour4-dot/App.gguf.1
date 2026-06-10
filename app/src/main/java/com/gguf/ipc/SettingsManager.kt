package com.gguf.ipc

import android.content.Context
import android.content.SharedPreferences

/**
 * SettingsManager — Persists all app settings via SharedPreferences.
 * Settings survive app restarts.
 */
object SettingsManager {

    private const val PREFS_NAME = "gguf_zerocopy_settings"

    private object Keys {
        const val N_CTX = "n_ctx"
        const val MAX_TOKENS = "max_tokens"
        const val TEMPERATURE = "temperature"
        const val TOP_P = "top_p"
        const val MIN_P = "min_p"
        const val GPU_LAYERS = "gpu_layers"
        const val THREADS = "threads"
        const val REPEAT_PENALTY = "repeat_penalty"
        const val FREQ_PENALTY = "freq_penalty"
        const val PRES_PENALTY = "pres_penalty"
        const val SYSTEM_PROMPT = "system_prompt"
        const val ENGINE_TYPE = "engine_type"
        const val AUTO_DETECT_DEVICE = "auto_detect_device"
    }

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var nCtx: Int
        get() = prefs?.getInt(Keys.N_CTX, 8192) ?: 8192
        set(value) = prefs?.edit()?.putInt(Keys.N_CTX, value)?.apply() ?: Unit

    var maxTokens: Int
        get() = prefs?.getInt(Keys.MAX_TOKENS, 4096) ?: 4096
        set(value) = prefs?.edit()?.putInt(Keys.MAX_TOKENS, value)?.apply() ?: Unit

    var temperature: Float
        get() = prefs?.getFloat(Keys.TEMPERATURE, 0.7f) ?: 0.7f
        set(value) = prefs?.edit()?.putFloat(Keys.TEMPERATURE, value)?.apply() ?: Unit

    var topP: Float
        get() = prefs?.getFloat(Keys.TOP_P, 0.9f) ?: 0.9f
        set(value) = prefs?.edit()?.putFloat(Keys.TOP_P, value)?.apply() ?: Unit

    var minP: Float
        get() = prefs?.getFloat(Keys.MIN_P, 0.05f) ?: 0.05f
        set(value) = prefs?.edit()?.putFloat(Keys.MIN_P, value)?.apply() ?: Unit

    var gpuLayers: Int
        get() = prefs?.getInt(Keys.GPU_LAYERS, 99) ?: 99
        set(value) = prefs?.edit()?.putInt(Keys.GPU_LAYERS, value)?.apply() ?: Unit

    var threads: Int
        get() = prefs?.getInt(Keys.THREADS, 4) ?: 4
        set(value) = prefs?.edit()?.putInt(Keys.THREADS, value)?.apply() ?: Unit

    var repeatPenalty: Float
        get() = prefs?.getFloat(Keys.REPEAT_PENALTY, 1.1f) ?: 1.1f
        set(value) = prefs?.edit()?.putFloat(Keys.REPEAT_PENALTY, value)?.apply() ?: Unit

    var freqPenalty: Float
        get() = prefs?.getFloat(Keys.FREQ_PENALTY, 0.0f) ?: 0.0f
        set(value) = prefs?.edit()?.putFloat(Keys.FREQ_PENALTY, value)?.apply() ?: Unit

    var presPenalty: Float
        get() = prefs?.getFloat(Keys.PRES_PENALTY, 0.0f) ?: 0.0f
        set(value) = prefs?.edit()?.putFloat(Keys.PRES_PENALTY, value)?.apply() ?: Unit

    var systemPrompt: String
        get() = prefs?.getString(Keys.SYSTEM_PROMPT, "You are a helpful, concise assistant running on-device. Respond clearly and directly.") ?: "You are a helpful, concise assistant running on-device. Respond clearly and directly."
        set(value) = prefs?.edit()?.putString(Keys.SYSTEM_PROMPT, value)?.apply() ?: Unit

    var engineType: String
        get() = prefs?.getString(Keys.ENGINE_TYPE, "LLAMA_CPP") ?: "LLAMA_CPP"
        set(value) = prefs?.edit()?.putString(Keys.ENGINE_TYPE, value)?.apply() ?: Unit

    var autoDetectDevice: Boolean
        get() = prefs?.getBoolean(Keys.AUTO_DETECT_DEVICE, true) ?: true
        set(value) = prefs?.edit()?.putBoolean(Keys.AUTO_DETECT_DEVICE, value)?.apply() ?: Unit

    fun toConfig(): InferenceEngine.Config {
        return InferenceEngine.Config(
            nCtx = nCtx,
            maxNewTokens = maxTokens,
            temperature = temperature,
            topP = topP,
            minP = minP,
            nGpuLayers = gpuLayers,
            nThreads = threads,
            seed = -1
        )
    }

    fun toRepeatPenaltyConfig(): InferenceEngine.RepeatPenaltyConfig {
        return InferenceEngine.RepeatPenaltyConfig(
            repeatPenalty = repeatPenalty,
            freqPenalty = freqPenalty,
            presPenalty = presPenalty
        )
    }

    fun applyToDeviceDefaults(deviceInfo: DeviceUtils.DeviceInfo) {
        val config = DeviceUtils.suggestConfig(deviceInfo)
        nCtx = config.nCtx
        maxTokens = config.maxNewTokens
        gpuLayers = config.nGpuLayers
        threads = config.nThreads
    }
}
