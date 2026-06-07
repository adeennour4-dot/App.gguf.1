
package com.gguf.ipc

import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import java.nio.ByteBuffer
import java.nio.ByteOrder

object EngineCore {
    private const val HEADER_SIZE = 16
    init { System.loadLibrary("ipc-bridge") }

    external fun initializeSharedMemoryNative(): Int
    external fun loadGgufModelNative(filePath: String): Boolean
    external fun executeZeroCopyInference(prompt: String)
    external fun getKvCacheUsageNative(): Int
    private external fun isInferenceDoneNative(): Boolean

    private var readBuffer: ByteBuffer? = null

    fun bootZeroCopyEngine() {
        val fd = initializeSharedMemoryNative()
        if (fd >= 0) {
            val pfd = ParcelFileDescriptor.fromFd(fd)
            val shm = SharedMemory.fromFileDescriptor(pfd)
            readBuffer = shm.mapReadOnly().apply { order(ByteOrder.LITTLE_ENDIAN) }
        }
    }

    fun loadModel(path: String): Boolean = loadGgufModelNative(path)

    fun readPartialStream(): String {
        val buf = readBuffer ?: return ""
        val pos = buf.getInt(0).and(0x7FFFFFFF)
        if (pos <= 0) return ""
        val bytes = ByteArray(pos.coerceAtMost(524288))
        buf.position(HEADER_SIZE)
        buf.get(bytes)
        return String(bytes, Charsets.UTF_8).trimEnd('\u0000')
    }

    fun getTpsScaled(): Float {
        val buf = readBuffer ?: return 0f
        // Read tps_scaled from Offset 12 in the shared memory header
        return buf.getInt(12) / 100f
    }

    fun isInferenceDone(): Boolean = isInferenceDoneNative()
}
