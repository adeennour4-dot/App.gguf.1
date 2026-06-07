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
    external fun getKvCacheUsageNative(): Int
    private external fun isInferenceDoneNative(): Boolean

    private var readBuffer: ByteBuffer? = null

    fun bootZeroCopyEngine() {
        val fd = initializeSharedMemoryNative()
        if (fd >= 0) {
            val shm = SharedMemory.fromFileDescriptor(ParcelFileDescriptor.fromFd(fd))
            readBuffer = shm.mapReadOnly().apply { order(ByteOrder.LITTLE_ENDIAN) }
        }
    }

    fun loadModel(path: String): Boolean = loadGgufModelNative(path)
    fun isInferenceDone(): Boolean = isInferenceDoneNative()
    fun getTpsScaled(): Float = (readBuffer?.getInt(12) ?: 0) / 100f

    fun readPartialStream(): String {
        val buf = readBuffer ?: return ""
        val pos = buf.getInt(0).and(0x7FFFFFFF)
        if (pos <= 0) return ""
        val bytes = ByteArray(pos.coerceAtMost(524288))
        buf.position(16)
        buf.get(bytes)
        return String(bytes, Charsets.UTF_8).trimEnd('\u0000')
    }
}
