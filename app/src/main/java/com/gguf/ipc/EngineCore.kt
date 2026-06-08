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
 *
 * Fixed for Samsung S23 FE (Exynos 2200) stability with smart hardware fallback.
 */
object EngineCore {

    private const val TAG = "GGUF_ZeroCopy_v5"
    private const val HEADER_SIZE       = 16          // 4 fields × 4 bytes
    private const val TOKEN_STREAM_SIZE = 524288       // 512 KB
    private const val TOTAL_SIZE        = HEADER_SIZE + TOKEN_STREAM_SIZE

    init { System.loadLibrary("ipc-bridge") }

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
    // Config (FIXED: Defaults are now hardware-safe values!)
    // -----------------------------------------------------------------------
    data class Config(
        val nCtx: Int          = 4096,         // Lower baseline context to save mobile VRAM
        val maxNewTokens: Int  = 2048,
        val temperature: Float = 0.7f,
        val topP: Float        = 0.9f,
        val minP: Float        = 0.05f,
        val nGpuLayers: Int    = 0,            // SAFE DEFAULT: Start at CPU-only, let auto-detect scale up
        val nThreads: Int      = 4,            // Dynamic setup handles this below
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
    
    /**
     * Scans your phone's processor and RAM to pick a safe, non-crashing GPU target.
     */
    fun autoDetectGpuLayers(context: Context): Int {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        
        val totalRamGb = memoryInfo.totalMem / (1024 * 1024 * 1024)
        val hardwareName = Build.HARDWARE.lowercase()
        val processorName = Build.BOARD.lowercase()

        Log.d(TAG, "Hardware Detected: HW=$hardwareName, BOARD=$processorName, RAM=${totalRamGb}GB")

        // Strict validation rules for the Exynos 2200 Xclipse GPU
        return when {
            hardwareName.contains("exynos") || processorName.contains("s5e9925") -> {
                if (totalRamGb >= 8) 16 else
