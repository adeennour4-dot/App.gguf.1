package com.gguf.ipc

import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import java.nio.ByteBuffer
import java.nio.ByteOrder

object EngineCore {
    init { System.loadLibrary("ipc-bridge") }

    // ── Native JNI declarations ──
    external fun initializeSharedMemoryNative(): Int
    external fun loadGgufModelNative(path: String): Boolean
    external fun setNativeConfig(
        nCtx: Int, maxNewTokens: Int, temperature: Float, topP: Float,
        minP: Float, nGpuLayers: Int, nThreads: Int, seed: Int
    )
    external fun setSystemPromptNative(prompt: String)
    external fun setRepeatPenaltyNative(repeatPenalty: Float, freqPenalty: Float, presPenalty: Float)
    external fun executeZeroCopyInference(prompt: String)
    external fun abortInferenceNative()
    external fun resetContextNative()
    external fun getKvCacheUsageNative(): Int
    external fun benchmarkNative(ppTokens: Int, tgTokens: Int): String
    external fun getModelInfoNative(): String

    // ── Data classes ──
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

    // ── Shared memory protocol ──
    // Header: write_pos(4) | flags(4) | tokens_gen(4) | tps_scaled(4) = 16 bytes
    // Data: 524272 bytes (stream buffer)
    private const val HEADER_SIZE = 16
    private const val STREAM_CAPACITY = 524288 - HEADER_SIZE
    private const val FLAG_DONE   = 1
    private const val FLAG_ACTIVE = 2

    private var readBuffer: ByteBuffer? = null
    private var lastReadPos = 0
    private var _totalTokens = 0

    // ── Lifecycle ──
    fun bootZeroCopyEngine() {
        val fd = initializeSharedMemoryNative()
        if (fd >= 0) {
            val shm = SharedMemory.fromFileDescriptor(ParcelFileDescriptor.fromFd(fd))
            readBuffer = shm.mapReadOnly().apply { order(ByteOrder.LITTLE_ENDIAN) }
        }
    }

    fun loadModel(path: String): Boolean {
        val ok = loadGgufModelNative(path)
        if (ok) {
            lastReadPos = 0
            _totalTokens = 0
        }
        return ok
    }

    fun setEngineConfig(cfg: Config) {
        setNativeConfig(
            cfg.nCtx, cfg.maxNewTokens, cfg.temperature, cfg.topP,
            cfg.minP, cfg.nGpuLayers, cfg.nThreads, cfg.seed
        )
    }

    fun setRepeatPenalty(cfg: RepeatPenaltyConfig) {
        setRepeatPenaltyNative(cfg.repeatPenalty, cfg.freqPenalty, cfg.presPenalty)
    }

    // ── Stream reading (pure Kotlin, from shared memory) ──
    private fun buf(): ByteBuffer = readBuffer
        ?: error("EngineCore not initialized — call bootZeroCopyEngine()")

    /** Returns new bytes since last call. */
    fun readPartialStream(): String {
        val b = buf()
        val pos = b.getInt(0) and 0x7FFFFFFF
        val cap = pos.coerceAtMost(STREAM_CAPACITY)
        // Detect reset: C++ reset write_pos to 0 but lastReadPos still holds old value
        if (cap < lastReadPos) lastReadPos = 0
        if (cap <= lastReadPos) return ""
        val len = cap - lastReadPos
        val bytes = ByteArray(len)
        b.position(HEADER_SIZE + lastReadPos)
        b.get(bytes)
        lastReadPos = cap
        return String(bytes, Charsets.UTF_8)
    }

    /** Reads everything written so far and resets cursor. */
    fun readTokenStream(): String {
        val b = buf()
        val pos = b.getInt(0) and 0x7FFFFFFF
        if (pos <= 0) return ""
        val size = pos.coerceAtMost(STREAM_CAPACITY)
        val bytes = ByteArray(size)
        b.position(HEADER_SIZE)
        b.get(bytes)
        lastReadPos = size
        return String(bytes, Charsets.UTF_8).trimEnd('\u0000')
    }

    fun getTokensGenerated(): Int = buf().getInt(8)
    fun isInferenceDone(): Boolean = (buf().getInt(4) and FLAG_DONE) != 0
    fun addTotalTokens(n: Int) { _totalTokens += n }
    fun totalTokens(): Int = _totalTokens
    fun resetTotalTokens() { _totalTokens = 0 }

    /** Placeholder — real export handled by UI layer. */
    fun exportChatHistoryNative(): String = ""
}
