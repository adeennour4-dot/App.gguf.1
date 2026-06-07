package com.gguf.ipc

import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import java.nio.ByteBuffer
import java.nio.ByteOrder

object EngineCore {
    init { System.loadLibrary("ipc-bridge") }

    external fun initializeSharedMemoryNative(): Int
    external fun loadGgufModelNative(path: String): Boolean
    external fun executeZeroCopyInference(prompt: String)
    external fun abortInferenceNative()
    external fun resetContextNative()
    external fun getKvCacheUsageNative(): Int

    private var readBuffer: ByteBuffer? = null

    fun boot() {
        val fd = initializeSharedMemoryNative()
        if (fd >= 0) {
            val shm = SharedMemory.fromFileDescriptor(ParcelFileDescriptor.fromFd(fd))
            readBuffer = shm.mapReadOnly().apply { order(ByteOrder.LITTLE_ENDIAN) }
        }
    }

    fun readStream(): String {
        val buf = readBuffer ?: return ""
        val pos = buf.getInt(0).and(0x7FFFFFFF) // Offset 0 is write_pos
        if (pos <= 0) return ""
        val bytes = ByteArray(pos.coerceAtMost(524288))
        buf.position(16) // Header is 16 bytes
        buf.get(bytes)
        return String(bytes).trimEnd('\u0000')
    }
}
