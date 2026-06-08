package com.gguf.ipc

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * EngineCore v5 — Kotlin bridge to the C++ llama.cpp JNI engine.
 * * Clean compiler pass verification optimized for ARMv8/ARMv9 execution.
 */
object EngineCore {

    private const val TAG = "GGUF_ZeroCopy_v5"
    private const val HEADER_SIZE       = 16          // 4 fields × 4 bytes
    private const val TOKEN_STREAM_SIZE = 524288       // 512 KB
    private const val TOTAL_SIZE        = HEADER_SIZE + TOKEN_STREAM_SIZE

    init { 
        System.loadLibrary("ipc-bridge") 
    }

    // -----------------------------------------------------------------------
    // Native declarations
    // -----------------------------------------------------------------------
    private external fun initializeSharedMemoryNative(): Int
    external  fun loadGgufModelNative(filePath: String): Boolean
    external  fun executeZeroCopyInference(prompt: String)
    private external fun getWritePosNative(): Int
    private external fun isInferenceDoneNative(): Boolean
    external  fun abortInferenceNative()

    external fun setEngineConfigNative(
        nCtx: Int, maxNewTokens: Int, temperature: Float,
        topP: Float, minP: Float, nGpuLayers: Int, nThreads: Int, seed: Int
    )
    external fun setSystemPromptNative(prompt: String)
    external fun resetContextNative()

    /** Returns JSON string: {"arch":"llama","params":7B,...} */
    external fun getModelInfoNative(): String

    /** Runs PP/TG benchmark; returns JSON: {"pp_tps":1200.0,"tg_tps":42.5} */
    external fun benchmarkNative(ppTokens: Int, tgTokens: Int): String

    /** Repetition control parameters */
    external fun setRepeatPenaltyNative(repeatPenalty: Float, freqPenalty: Float, presPenalty: Float)

    /** Returns the full conversation history as plain text */
    external fun exportChatHistoryNative(): String

    /** Returns 0-100 KV cache fill % */
    external fun getKvCacheUsageNative(): Int

    // -----------------------------------------------------------------------
    // Shared memory
    // -----------------------------------------------------------------------
    private var sharedMemory: SharedMemory? = null
    private var readBuffer: ByteBuffer? = null

    // -----------------------------------------------------------------------
    // Config Definitions
    // -----------------------------------------------------------------------
    data class Config(
        val nCtx: Int          = 4096,
        val maxNewTokens: Int  = 2048,
        val temperature: Float = 0.7f,
        val topP: Float        = 0.9f,
        val minP: Float        = 0.05f,
        val nGpuLayers: Int    = 0,
        val nThreads: Int      = 4,
        val seed: Int          = -1
    )

    data class RepeatPenaltyConfig(
        val repeatPenalty: Float = 1.1f,
        val freqPenalty: Float   = 0.0f,
        val presPenalty: Float   = 0.0f
    )

    // -----------------------------------------------------------------------
    // Smart Hardware Auto-Detection Methods
    // -----------------------------------------------------------------------
    
    fun autoDetectGpuLayers(context: Context): Int {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        
        val totalRamGb = memoryInfo.totalMem / (1024 * 1024 * 1024)
        val hardwareName = Build.HARDWARE.lowercase()
        val processorName = Build.BOARD.lowercase()

        Log.d(TAG, "Hardware Detected: HW=$hardwareName, BOARD=$processorName, RAM=${totalRamGb}GB")

        return when {
            hardwareName.contains("exynos") || processorName.contains("s5e9925") -> {
                if (totalRamGb >= 8) 16 else 0
            }
            else -> {
                if (totalRamGb >= 12) 32 else 24
            }
        }
    }

    fun autoDetectThreads(): Int {
        val totalCores = Runtime.getRuntime().availableProcessors()
        return if (totalCores > 4) totalCores - 2 else 4
    }

    fun setEngineConfig(cfg: Config) {
        setEngineConfigNative(
            cfg.nCtx, cfg.maxNewTokens,
            cfg.temperature, cfg.topP, cfg.minP,
            cfg.nGpuLayers, cfg.nThreads, cfg.seed
        )
    }

    fun setRepeatPenalty(cfg: RepeatPenaltyConfig) {
        setRepeatPenaltyNative(cfg.repeatPenalty, cfg.freqPenalty, cfg.presPenalty)
    }

    // -----------------------------------------------------------------------
    // Boot Engine Linker Execution
    // -----------------------------------------------------------------------
    fun bootZeroCopyEngine() {
        val nativeFd = initializeSharedMemoryNative()
        if (nativeFd < 0) {
            Log.e(TAG, "initializeSharedMemoryNative returned $nativeFd")
            return
        }
        try {
            val pfd    = ParcelFileDescriptor.fromFd(nativeFd)
            val dupPfd = pfd.dup()
            pfd.close() 
            sharedMemory = SharedMemory.fromFileDescriptor(dupPfd)
            dupPfd.close()
            readBuffer = sharedMemory!!.mapReadOnly().apply { order(ByteOrder.LITTLE_ENDIAN) }
            Log.i(TAG, "Shared ring buffer mapped safely.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to map shared memory: ${e.message}", e)
        }
    }

    fun loadModel(path: String): Boolean {
        Log.i(TAG, "Loading model: $path")
        return loadGgufModelNative(path)
    }

    // -----------------------------------------------------------------------
    // Stream reading
    // -----------------------------------------------------------------------
    fun readPartialStream(): String {
        val buf = readBuffer ?: return ""
        buf.position(0)
        val writePos = buf.int.and(0x7FFFFFFF).coerceAtMost(TOKEN_STREAM_SIZE)
        if (writePos == 0) return ""
        buf.position(HEADER_SIZE)
        val bytes = ByteArray(writePos)
        buf.get(bytes)
        return String(bytes, Charsets.UTF_8).trimEnd('\u0000')
    }

    fun readTokenStream(): String = readPartialStream()

    fun getTokensGenerated(): Int {
        val buf = readBuffer ?: return 0
        buf.position(8)
        return buf.int.and(0x7FFFFFFF)
    }

    fun getWritePos(): Int         = getWritePosNative()
    fun isInferenceDone(): Boolean = isInferenceDoneNative()
}
