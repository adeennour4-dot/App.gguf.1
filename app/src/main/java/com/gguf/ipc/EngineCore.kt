package com.gguf.ipc

import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

object EngineCore {
    private const val TAG = "GGUF_CORE_V5"
    private const val HEADER_SIZE = 16
    private const val TOKEN_STREAM_SIZE = 524288 

    init {
        System.loadLibrary("ipc-bridge")
    }

    // Matches MainActivity: EngineCore.Config
    data class Config(
        val nCtx: Int = 8192,
        val maxNewTokens: Int = 4096,
        val temperature: Float = 0.7f,
        val topP: Float = 0.9f,
        val minP: Float = 0.05f,
        val nGpuLayers: Int = 99,
        val nThreads: Int = 4,
        val seed: Int = -1
    )

    // Matches MainActivity: EngineCore.RepeatPenaltyConfig
    data class RepeatPenaltyConfig(
        val repeatPenalty: Float = 1.1f,
        val freqPenalty: Float = 0.0f,
        val presPenalty: Float = 0.0f
    )

    external fun initializeSharedMemoryNative(): Int
    external fun loadGgufModelNative(filePath: String): Boolean
    external fun executeZeroCopyInference(prompt: String)
    external fun abortInferenceNative()
    external fun resetContextNative()
    external fun getKvCacheUsageNative(): Int
    external fun getModelInfoNative(): String
    external fun benchmarkNative(ppTokens: Int, tgTokens: Int): String
    external fun exportChatHistoryNative(): String
    external fun setSystemPromptNative(prompt: String)
    external fun setEngineConfigNative(nCtx: Int, maxNewTokens: Int, temp: Float, topP: Float, minP: Float, gpu: Int, threads: Int, seed: Int)
    external fun setRepeatPenaltyNative(repeat: Float, freq: Float, pres: Float)
    private external fun isInferenceDoneNative(): Boolean

    private var readBuffer: ByteBuffer? = null

    // Matches MainActivity: EngineCore.bootZeroCopyEngine()
    fun bootZeroCopyEngine() {
        try {
            val fd = initializeSharedMemoryNative()
            if (fd >= 0) {
                val pfd = ParcelFileDescriptor.fromFd(fd)
                val shm = SharedMemory.fromFileDescriptor(pfd)
                readBuffer = shm.mapReadOnly().apply { order(ByteOrder.LITTLE_ENDIAN) }
            }
        } catch (e: Exception) { Log.e(TAG, "Boot error: ${e.message}") }
    }

    // Matches MainActivity: EngineCore.loadModel(path)
    fun loadModel(path: String): Boolean = loadGgufModelNative(path)

    // Matches MainActivity: EngineCore.setEngineConfig(cfg)
    fun setEngineConfig(cfg: Config) {
        setEngineConfigNative(cfg.nCtx, cfg.maxNewTokens, cfg.temperature, cfg.topP, cfg.minP, cfg.nGpuLayers, cfg.nThreads, cfg.seed)
    }

    // Matches MainActivity: EngineCore.setRepeatPenalty(cfg)
    fun setRepeatPenalty(cfg: RepeatPenaltyConfig) {
        setRepeatPenaltyNative(cfg.repeatPenalty, cfg.freqPenalty, cfg.presPenalty)
    }

    fun readPartialStream(): String {
        val buf = readBuffer ?: return ""
        val pos = buf.getInt(0).and(0x7FFFFFFF)
        if (pos <= 0) return ""
        val bytes = ByteArray(pos.coerceAtMost(TOKEN_STREAM_SIZE))
        buf.position(HEADER_SIZE)
        buf.get(bytes)
        return String(bytes, Charsets.UTF_8).trimEnd('\u0000')
    }

    // Matches MainActivity: EngineCore.readTokenStream()
    fun readTokenStream(): String = readPartialStream()

    // Matches MainActivity: EngineCore.getTokensGenerated()
    fun getTokensGenerated(): Int {
        val buf = readBuffer ?: return 0
        return buf.getInt(8)
    }

    // Matches MainActivity: EngineCore.isInferenceDone()
    fun isInferenceDone(): Boolean = isInferenceDoneNative()
}
