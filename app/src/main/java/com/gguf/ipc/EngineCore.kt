package com.gguf.ipc

import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

object EngineCore {
    private const val TAG = "GGUF_CORE_V5"
    private const val HEADER_SIZE = 16
    private const val TOKEN_STREAM_SIZE = 524288 // 512KB

    init {
        System.loadLibrary("ipc-bridge")
    }

    // --- Data Classes required by MainActivity ---
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

    data class RepeatPenaltyConfig(
        val repeatPenalty: Float = 1.1f,
        val freqPenalty: Float = 0.0f,
        val presPenalty: Float = 0.0f
    )

    // --- Native Declarations ---
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
    
    external fun setEngineConfigNative(
        nCtx: Int, maxNewTokens: Int, temperature: Float,
        topP: Float, minP: Float, nGpuLayers: Int, nThreads: Int, seed: Int
    )

    external fun setRepeatPenaltyNative(repeatPenalty: Float, freqPenalty: Float, presPenalty: Float)
    
    // Polling helpers
    private external fun isInferenceDoneNative(): Boolean

    // --- Logic used by MainActivity ---
    private var readBuffer: ByteBuffer? = null

    /**
     * Matches MainActivity call: EngineCore.bootZeroCopyEngine()
     */
    fun bootZeroCopyEngine() {
        try {
            val fd = initializeSharedMemoryNative()
            if (fd >= 0) {
                val pfd = ParcelFileDescriptor.fromFd(fd)
                val shm = SharedMemory.fromFileDescriptor(pfd)
                readBuffer = shm.mapReadOnly().apply { order(ByteOrder.LITTLE_ENDIAN) }
                Log.i(TAG, "Engine initialized via FD: $fd")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Boot error: ${e.message}")
        }
    }

    /**
     * Matches MainActivity call: EngineCore.loadModel(path)
     */
    fun loadModel(path: String): Boolean = loadGgufModelNative(path)

    /**
     * Matches MainActivity call: EngineCore.setEngineConfig(cfg)
     */
    fun setEngineConfig(cfg: Config) {
        setEngineConfigNative(
            cfg.nCtx, cfg.maxNewTokens, cfg.temperature,
            cfg.topP, cfg.minP, cfg.nGpuLayers, cfg.nThreads, cfg.seed
        )
    }

    /**
     * Matches MainActivity call: EngineCore.setRepeatPenalty(cfg)
     */
    fun setRepeatPenalty(cfg: RepeatPenaltyConfig) {
        setRepeatPenaltyNative(cfg.repeatPenalty, cfg.freqPenalty, cfg.presPenalty)
    }

    /**
     * Polling logic for text generation
     */
    fun readPartialStream(): String {
        val buf = readBuffer ?: return ""
        // Offset 0: write_pos
        val pos = buf.getInt(0).and(0x7FFFFFFF)
        if (pos <= 0) return ""
        
        val bytes = ByteArray(pos.coerceAtMost(TOKEN_STREAM_SIZE))
        val oldPos = buf.position()
        buf.position(HEADER_SIZE)
        buf.get(bytes)
        buf.position(oldPos)
        
        return String(bytes, Charsets.UTF_8).trimEnd('\u0000')
    }

    /**
     * Matches MainActivity call: EngineCore.readTokenStream()
     */
    fun readTokenStream(): String = readPartialStream()

    /**
     * Matches MainActivity call: EngineCore.getTokensGenerated()
     */
    fun getTokensGenerated(): Int {
        val buf = readBuffer ?: return 0
        // Offset 8: tokens_gen
        return buf.getInt(8)
    }

    /**
     * Matches MainActivity call: EngineCore.isInferenceDone()
     */
    fun isInferenceDone(): Boolean = isInferenceDoneNative()
}
