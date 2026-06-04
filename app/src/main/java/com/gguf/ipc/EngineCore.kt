package com.gguf.ipc

import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * EngineCore v3 — Kotlin bridge to the C++ llama.cpp JNI engine.
 *
 * New in v3:
 *   - TOKEN_STREAM_SIZE raised to 256 KB (matches ipc-bridge.cpp)
 *   - setEngineConfig() exposes all context/sampling parameters
 *   - setSystemPrompt() for per-model or per-session system prompts
 *   - resetContext() clears conversation history + KV cache
 *   - executeZeroCopyInference() is the only public inference call
 *     (no separate runInference / runInferenceStreaming confusion)
 *
 * Memory layout of shared ring buffer:
 *   Offset 0 : UInt32  write_pos      — bytes written so far
 *   Offset 4 : UInt32  flags          — bit 0 = inference done
 *   Offset 8 : Byte[]  token_stream   — UTF-8 response bytes
 */
object EngineCore {

    private const val TAG = "GGUF_ZeroCopy"
    private const val HEADER_SIZE       = 8
    private const val TOKEN_STREAM_SIZE = 262144   // 256 KB — must match C++
    private const val TOTAL_SIZE        = HEADER_SIZE + TOKEN_STREAM_SIZE

    init { System.loadLibrary("ipc-bridge") }

    // -----------------------------------------------------------------------
    // Native declarations
    // -----------------------------------------------------------------------
    private external fun initializeSharedMemoryNative(): Int
    external fun loadGgufModelNative(filePath: String): Boolean
    external fun executeZeroCopyInference(prompt: String)
    private external fun getWritePosNative(): Int
    private external fun isInferenceDoneNative(): Boolean

    /** Apply all context + sampling settings before loadGgufModelNative(). */
    external fun setEngineConfigNative(
        nCtx: Int,
        maxNewTokens: Int,
        temperature: Float,
        topP: Float,
        minP: Float,
        nGpuLayers: Int,
        seed: Int
    )

    /** Override the system prompt (can be called before any inference). */
    external fun setSystemPromptNative(prompt: String)

    /** Clear conversation history and KV cache (start fresh). */
    external fun resetContextNative()

    // -----------------------------------------------------------------------
    // Shared memory state
    // -----------------------------------------------------------------------
    private var sharedMemory: SharedMemory? = null
    private var readBuffer: ByteBuffer? = null

    // -----------------------------------------------------------------------
    // Default config — change via setEngineConfig() before loading a model
    // -----------------------------------------------------------------------
    data class Config(
        val nCtx: Int         = 8192,
        val maxNewTokens: Int = 4096,
        val temperature: Float= 0.7f,
        val topP: Float       = 0.9f,
        val minP: Float       = 0.05f,
        val nGpuLayers: Int   = 99,
        val seed: Int         = -1          // -1 = LLAMA_DEFAULT_SEED (random)
    )

    fun setEngineConfig(cfg: Config) {
        setEngineConfigNative(
            cfg.nCtx, cfg.maxNewTokens,
            cfg.temperature, cfg.topP, cfg.minP,
            cfg.nGpuLayers, cfg.seed
        )
    }

    // -----------------------------------------------------------------------
    // bootZeroCopyEngine — call once on app start
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
            sharedMemory = SharedMemory.fromFileDescriptor(dupPfd)
            dupPfd.close()
            readBuffer = sharedMemory!!.mapReadOnly().apply {
                order(ByteOrder.LITTLE_ENDIAN)
            }
            Log.i(TAG, "Shared ring buffer mapped. size=$TOTAL_SIZE")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to map shared memory: ${e.message}", e)
        }
    }

    // -----------------------------------------------------------------------
    // loadModel — convenience wrapper
    // -----------------------------------------------------------------------
    fun loadModel(path: String): Boolean {
        Log.i(TAG, "Loading model: $path")
        return loadGgufModelNative(path)
    }

    // -----------------------------------------------------------------------
    // readPartialStream — safe to call mid-inference for UI updates
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

    // -----------------------------------------------------------------------
    // readTokenStream — final read after inference completes
    // -----------------------------------------------------------------------
    fun readTokenStream(): String = readPartialStream()

    fun getWritePos(): Int = getWritePosNative()
    fun isInferenceDone(): Boolean = isInferenceDoneNative()
}
