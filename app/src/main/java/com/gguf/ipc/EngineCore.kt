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

    // Native JNI functions
    external fun initializeSharedMemoryNative(): Int
    external fun loadGgufModelNative(filePath: String): Boolean
    external fun executeZeroCopyInference(prompt: String)
    external fun abortInferenceNative()
    external fun resetContextNative()
    external fun getKvCacheUsageNative(): Int
    private external fun isInferenceDoneNative(): Boolean

    private var readBuffer: ByteBuffer? = null

    /**
     * Initializes shared memory and maps the read-only buffer
     */
    fun bootZeroCopyEngine() {
        try {
            val fd = initializeSharedMemoryNative()
            if (fd >= 0) {
                val pfd = ParcelFileDescriptor.fromFd(fd)
                val shm = SharedMemory.fromFileDescriptor(pfd)
                readBuffer = shm.mapReadOnly().apply { order(ByteOrder.LITTLE_ENDIAN) }
                Log.i(TAG, "Shared Memory Mapped via FD: $fd")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Boot failed: ${e.message}")
        }
    }

    /**
     * Reads output text from the shared memory ring buffer
     */
    fun readPartialStream(): String {
        val buf = readBuffer ?: return ""
        // Offset 0: write_pos (uint32)
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
     * Returns real-time TPS from the header (Offset 12)
     */
    fun getTpsScaled(): Float {
        val buf = readBuffer ?: return 0f
        // Offset 12: tps_scaled (uint32)
        val scaled = buf.getInt(12)
        return scaled / 100f
    }

    fun isInferenceDone(): Boolean = isInferenceDoneNative()
}
