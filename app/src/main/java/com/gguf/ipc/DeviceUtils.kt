package com.gguf.ipc

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

/**
 * DeviceUtils — Detects CPU, GPU, RAM, and suggests optimal inference settings.
 * All detection is done via public Android APIs and /proc filesystem.
 */
object DeviceUtils {

    data class DeviceInfo(
        val cpuModel: String = "",
        val cpuCores: Int = Runtime.getRuntime().availableProcessors(),
        val cpuMaxFreq: Int = 0,
        val bigCores: List<Int> = emptyList(),
        val littleCores: List<Int> = emptyList(),
        val gpuVendor: String = "",
        val gpuRenderer: String = "",
        val totalRamMB: Long = 0,
        val availableRamMB: Long = 0,
        val isSnapdragon: Boolean = false,
        val isExynos: Boolean = false,
        val isMediaTek: Boolean = false,
        val isTensor: Boolean = false,
        val socModel: String = ""
    )

    /**
     * Detect complete device information.
     */
    fun detectDevice(context: Context): DeviceInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val totalRamMB = memInfo.totalMem / (1024 * 1024)
        val availableRamMB = memInfo.availMem / (1024 * 1024)

        val cpuModel = getCpuModel()
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val cpuMaxFreq = getCpuMaxFreq()
        val bigCores = detectBigCores()
        val littleCores = (0 until cpuCores).filter { it !in bigCores }

        val socModel = Build.SOC_MODEL.ifEmpty { Build.HARDWARE }.ifEmpty { "unknown" }
        val isSnapdragon = socModel.lowercase().contains("snapdragon") ||
                socModel.lowercase().contains("qcom") ||
                Build.MANUFACTURER.lowercase().contains("qualcomm")
        val isExynos = socModel.lowercase().contains("exynos") ||
                Build.MANUFACTURER.lowercase().contains("samsung")
        val isMediaTek = socModel.lowercase().contains("mt") ||
                socModel.lowercase().contains("dimensity") ||
                Build.MANUFACTURER.lowercase().contains("mediatek")
        val isTensor = socModel.lowercase().contains("tensor") ||
                Build.MANUFACTURER.lowercase().contains("google")

        return DeviceInfo(
            cpuModel = cpuModel,
            cpuCores = cpuCores,
            cpuMaxFreq = cpuMaxFreq,
            bigCores = bigCores,
            littleCores = littleCores,
            gpuVendor = "", // Would need GLSurfaceView to detect
            gpuRenderer = "", // Would need GLSurfaceView to detect
            totalRamMB = totalRamMB,
            availableRamMB = availableRamMB,
            isSnapdragon = isSnapdragon,
            isExynos = isExynos,
            isMediaTek = isMediaTek,
            isTensor = isTensor,
            socModel = socModel
        )
    }

    /**
     * Suggest optimal configuration based on device.
     */
    fun suggestConfig(deviceInfo: DeviceInfo, modelSizeB: Float = 7f): InferenceEngine.Config {
        val suggestedThreads = if (deviceInfo.bigCores.isNotEmpty()) {
            deviceInfo.bigCores.size.coerceAtMost(4)
        } else {
            (deviceInfo.cpuCores / 2).coerceIn(1, 4)
        }

        val suggestedGpuLayers = when {
            deviceInfo.isSnapdragon -> 99   // OpenCL works well on Adreno
            deviceInfo.isMediaTek -> 0     // Mali OpenCL often slower than CPU
            deviceInfo.isExynos -> 0       // Xclipse Vulkan unstable
            deviceInfo.isTensor -> 0       // Mali Vulkan varies
            else -> 0                      // Unknown GPU, be conservative
        }

        // Context size based on available RAM and model size
        val estimatedModelRAM = modelSizeB * 1024 * 0.6f // Rough estimate: 60% of model params in MB
        val availableForContext = (deviceInfo.availableRamMB - estimatedModelRAM).coerceAtLeast(512f)
        val suggestedCtx = when {
            modelSizeB <= 1f -> 8192
            modelSizeB <= 3f -> 4096
            modelSizeB <= 7f -> 2048
            else -> 1024
        }.coerceAtMost((availableForContext * 2).toInt()) // ~0.5 bytes per token in Q4_K_M KV cache
            .coerceAtLeast(2048)                          // Never go below 2K — RAM guard handles OOM

        return InferenceEngine.Config(
            nCtx = suggestedCtx,
            maxNewTokens = 2048,
            temperature = 0.7f,
            topP = 0.9f,
            minP = 0.05f,
            nGpuLayers = suggestedGpuLayers,
            nThreads = suggestedThreads,
            seed = -1
        )
    }

    /**
     * Check if device has enough RAM to load a model of given size (in GB).
     */
    fun canFitModel(context: Context, modelSizeGB: Float, safetyMargin: Float = 0.8f): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val availableMB = memInfo.availMem / (1024 * 1024)
        val requiredMB = (modelSizeGB * 1024 * 1.2f) // 20% overhead for inference
        return availableMB > requiredMB / safetyMargin
    }

    /**
     * Get CPU model name from /proc/cpuinfo.
     */
    private fun getCpuModel(): String {
        return try {
            File("/proc/cpuinfo").readLines()
                .firstOrNull { it.startsWith("Hardware") || it.startsWith("model name") }
                ?.substringAfter(":")?.trim()
                ?: Build.HARDWARE
        } catch (e: Exception) {
            Build.HARDWARE
        }
    }

    /**
     * Get maximum CPU frequency.
     */
    private fun getCpuMaxFreq(): Int {
        var maxFreq = 0
        for (cpu in 0 until Runtime.getRuntime().availableProcessors()) {
            try {
                val freq = File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")
                    .readText().trim().toIntOrNull() ?: 0
                if (freq > maxFreq) maxFreq = freq
            } catch (_: Exception) {}
        }
        return maxFreq
    }

    /**
     * Detect big cores on ARM big.LITTLE architecture.
     */
    private fun detectBigCores(): List<Int> {
        val coreFreqs = mutableListOf<Pair<Int, Int>>()
        val cpuCount = Runtime.getRuntime().availableProcessors()

        for (cpu in 0 until cpuCount) {
            try {
                val freq = File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")
                    .readText().trim().toIntOrNull() ?: 0
                coreFreqs.add(cpu to freq)
            } catch (_: Exception) {}
        }

        if (coreFreqs.isEmpty()) return emptyList()

        val maxFreq = coreFreqs.maxOfOrNull { it.second } ?: return emptyList()
        val threshold = maxFreq * 80 / 100

        return coreFreqs.filter { it.second >= threshold }.map { it.first }
    }

    /**
     * Get suggested engine type based on device GPU.
     */
    fun suggestEngine(deviceInfo: DeviceInfo): InferenceEngine.EngineType {
        return when {
            deviceInfo.isSnapdragon -> InferenceEngine.EngineType.LLAMA_CPP  // OpenCL for Adreno
            deviceInfo.isMediaTek -> InferenceEngine.EngineType.MNN         // MNN CPU is faster
            deviceInfo.isExynos -> InferenceEngine.EngineType.LLAMA_CPP     // Vulkan for Xclipse
            deviceInfo.isTensor -> InferenceEngine.EngineType.LLAMA_CPP     // Vulkan for Mali
            else -> InferenceEngine.EngineType.MNN                          // MNN CPU fallback
        }
    }
}
