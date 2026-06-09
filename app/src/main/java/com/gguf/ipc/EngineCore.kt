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
 */
object EngineCore {

    private const val TAG = "GGUF_ZeroCopy_v5"
    private const val HEADER_SIZE       = 16
    private const val TOKEN_STREAM_SIZE = 524288
    private const val TOTAL_SIZE        = HEADER_SIZE + TOKEN_STREAM_SIZE

    init { System.loadLibrary("ipc-bridge") }

    // ── Native declarations ─────────────────────────────────────────────────
    private external fun initializeSharedMemoryNative(): Int
    external  fun loadGgufModelNative(filePath: String): Boolean
    external  fun executeZeroCopyInference(prompt: String)
    private external fun getWritePosNative(): Int
    private external fun isInferenceDoneNative(): Boolean
    external  fun abortInferenceNative()
    external  fun setEngineConfigNative(
        nCtx: Int, maxNewTokens: Int, temperature: Float,
        topP: Float, minP: Float, nGpuLayers: Int, nThreads: Int, seed: Int
    )
    external  fun setSystemPromptNative(prompt: String)
    external  fun resetContextNative()
    external  fun getModelInfoNative(): String
    external  fun benchmarkNative(ppTokens: Int, tgTokens: Int): String
    external  fun setRepeatPenaltyNative(repeatPenalty: Float, freqPenalty: Float, presPenalty: Float)
    external  fun exportChatHistoryNative(): String
    external  fun getKvCacheUsageNative(): Int

    // ── Shared memory ───────────────────────────────────────────────────────
    private var sharedMemory: SharedMemory? = null
    private var readBuffer: ByteBuffer?     = null

    // ── Config ──────────────────────────────────────────────────────────────
    data class Config(
        val nCtx: Int          = 8192,
        val maxNewTokens: Int  = 4096,
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

    // ── Hardware detection ──────────────────────────────────────────────────
    /**
     * Returns a human-readable GPU tier label for display in the UI.
     */
    fun getGpuTierLabel(context: Context): String {
        val hw    = Build.HARDWARE.lowercase()
        val board = Build.BOARD.lowercase()
        val soc   = Build.SOC_MODEL?.lowercase() ?: ""

        return when {
            hw.contains("qcom") || board.contains("qcom") || soc.contains("snapdragon") ||
            board.contains("kona") || board.contains("kalama") || board.contains("pineapple") ->
                "Snapdragon (Adreno GPU ✓)"
            hw.contains("exynos") || board.contains("s5e") || board.contains("exynos") ->
                "Exynos (GPU limited — CPU mode recommended)"
            hw.contains("mt") || board.contains("mt") || board.contains("mediatek") ->
                "MediaTek Dimensity (GPU partial)"
            hw.contains("tensor") || board.contains("slider") || board.contains("gs") ->
                "Google Tensor (GPU partial)"
            else ->
                "Unknown SoC (using safe defaults)"
        }
    }

    /**
     * Returns recommended GPU layers based on SoC and RAM.
     * Exynos → 0 (unreliable Vulkan), Snapdragon → partial or full offload.
     */
    fun autoDetectGpuLayers(context: Context): Int {
        val am   = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem  = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val ramGb = mem.totalMem / (1024L * 1024 * 1024)
        val hw    = Build.HARDWARE.lowercase()
        val board = Build.BOARD.lowercase()
        val soc   = Build.SOC_MODEL?.lowercase() ?: ""

        Log.d(TAG, "HW=$hw BOARD=$board SOC=$soc RAM=${ramGb}GB")

        return when {
            // Exynos — Vulkan backend unreliable; keep on CPU
            hw.contains("exynos") || board.contains("s5e") || board.contains("exynos") -> 0

            // Snapdragon — full or partial GPU offload based on RAM
            hw.contains("qcom") || board.contains("qcom") || soc.contains("snapdragon") ||
            board.contains("kona") || board.contains("kalama") || board.contains("pineapple") ->
                if (ramGb >= 12) 99 else if (ramGb >= 8) 32 else 16

            // MediaTek / Tensor — conservative
            else -> if (ramGb >= 12) 24 else 8
        }
    }

    fun autoDetectThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return if (cores > 4) cores - 2 else 4
    }

    // ── Config helpers ──────────────────────────────────────────────────────
    fun setEngineConfig(cfg: Config) = setEngineConfigNative(
        cfg.nCtx, cfg.maxNewTokens, cfg.temperature, cfg.topP, cfg.minP,
        cfg.nGpuLayers, cfg.nThreads, cfg.seed
    )

    fun setRepeatPenalty(cfg: RepeatPenaltyConfig) =
        setRepeatPenaltyNative(cfg.repeatPenalty, cfg.freqPenalty, cfg.presPenalty)

    // ── Boot ────────────────────────────────────────────────────────────────
    fun bootZeroCopyEngine() {
        val nativeFd = initializeSharedMemoryNative()
        if (nativeFd < 0) { Log.e(TAG, "initializeSharedMemoryNative failed: $nativeFd"); return }
        try {
            val pfd    = ParcelFileDescriptor.fromFd(nativeFd)
            val dupPfd = pfd.dup()
            pfd.close()
            sharedMemory = SharedMemory.fromFileDescriptor(dupPfd)
            dupPfd.close()
            readBuffer = sharedMemory!!.mapReadOnly().apply { order(ByteOrder.LITTLE_ENDIAN) }
            Log.i(TAG, "Ring buffer mapped OK.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to map shared memory: ${e.message}", e)
        }
    }

    fun loadModel(path: String): Boolean {
        Log.i(TAG, "loadModel: $path")
        return loadGgufModelNative(path)
    }

    // ── Stream reading ──────────────────────────────────────────────────────
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
