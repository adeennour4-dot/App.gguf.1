package com.gguf.ipc

import android.os.Build
import java.io.File

object DeviceUtils {

    data class CpuInfo(
        val cores: Int,
        val architecture: String,
        val features: List<String>,
        val isBigLittle: Boolean,
        val maxFrequencyMHz: Int,
        val suggestedThreads: Int,
        val suggestedGpuLayers: Int,
        val cpuPartNames: List<String>
    )

    data class GpuInfo(
        val renderer: String,
        val vendor: String,
        val version: String,
        val hasVulkan: Boolean
    )

    fun detectCpu(): CpuInfo {
        val cpuInfoText = readProcCpuInfo()
        val cores = countCpuCores()
        val features = parseCpuFeatures(cpuInfoText)
        val isBigLittle = detectBigLittle()
        val maxFreq = readMaxCpuFreq()
        val partNames = extractPartNames(cpuInfoText)
        val suggestedThreads = calculateSuggestedThreads(cores, isBigLittle)
        val suggestedGpuLayers = calculateSuggestedGpuLayers()
        val arch = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

        return CpuInfo(
            cores = cores,
            architecture = arch,
            features = features,
            isBigLittle = isBigLittle,
            maxFrequencyMHz = maxFreq,
            suggestedThreads = suggestedThreads,
            suggestedGpuLayers = suggestedGpuLayers,
            cpuPartNames = partNames
        )
    }

    fun detectGpu(): GpuInfo {
        val renderer = Build.MODEL
        val vendor = Build.MANUFACTURER
        val soc = SystemProperties("ro.chipname", "unknown")
        return GpuInfo(
            renderer = renderer,
            vendor = vendor,
            version = soc,
            hasVulkan = vulkanAvailable()
        )
    }

    fun vramBytes(): Long {
        val memInfo = readProcMemInfo()
        return memInfo["MemTotal"]?.toLongOrNull()?.times(1024) ?: 0L
    }

    fun autoConfigJson(): String {
        val cpu = detectCpu()
        return """{
  "n_threads": ${cpu.suggestedThreads},
  "n_gpu_layers": ${cpu.suggestedGpuLayers},
  "n_ctx": 8192,
  "n_batch": 2048,
  "max_new_tokens": ${if (cpu.suggestedGpuLayers > 0) 4096 else 2048},
  "temperature": 0.7,
  "top_p": 0.9,
  "min_p": 0.05,
  "repeat_penalty": 1.1,
  "freq_penalty": 0.0,
  "pres_penalty": 0.0,
  "description": "${cpu.cores} cores | ${cpu.architecture} | ${if (cpu.isBigLittle) "big.LITTLE" else "homogenous"}"
}"""
    }

    // ── Private helpers ──

    private fun readProcCpuInfo(): String =
        try { File("/proc/cpuinfo").readText() } catch (_: Exception) { "" }

    private fun readProcMemInfo(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            File("/proc/meminfo").readLines().forEach { line ->
                val parts = line.split(":")
                if (parts.size == 2) map[parts[0].trim()] = parts[1].trim().removeSuffix(" kB")
            }
        } catch (_: Exception) {}
        return map
    }

    private fun countCpuCores(): Int {
        try {
            val possible = File("/sys/devices/system/cpu/possible").readText().trim()
            val range = possible.substringAfter("0-").toIntOrNull()
            if (range != null) return range + 1
        } catch (_: Exception) {}
        return Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    }

    private fun detectBigLittle(): Boolean {
        try {
            val present = File("/sys/devices/system/cpu/present").readText().trim()
            val parts = present.split("-")
            if (parts.size == 2) {
                val total = parts[1].toIntOrNull() ?: return false
                var maxFreq = 0
                var minFreq = Int.MAX_VALUE
                for (i in 0..total) {
                    val f = cpuMaxFreq(i)
                    if (f > 0) {
                        if (f > maxFreq) maxFreq = f
                        if (f < minFreq) minFreq = f
                    }
                }
                return minFreq < Int.MAX_VALUE && maxFreq > 0 && (maxFreq.toFloat() / minFreq) > 1.4f
            }
        } catch (_: Exception) {}
        return false
    }

    private fun cpuMaxFreq(cpu: Int): Int {
        val paths = listOf(
            "/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq",
            "/sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_max_freq"
        )
        for (p in paths) {
            try {
                return File(p).readText().trim().toIntOrNull()?.div(1000) ?: 0
            } catch (_: Exception) {}
        }
        return 0
    }

    private fun readMaxCpuFreq(): Int {
        try {
            val present = File("/sys/devices/system/cpu/present").readText().trim()
            val parts = present.split("-")
            if (parts.size == 2) {
                val total = parts[1].toIntOrNull() ?: return 0
                var maxFreq = 0
                for (i in 0..total) {
                    val f = cpuMaxFreq(i)
                    if (f > maxFreq) maxFreq = f
                }
                return maxFreq
            }
        } catch (_: Exception) {}
        return 0
    }

    private fun parseCpuFeatures(text: String): List<String> {
        val features = mutableListOf<String>()
        text.lines().forEach { line ->
            if (line.trimStart().startsWith("Features")) {
                val vals = line.substringAfter(":").trim().split("\\s+".toRegex())
                features.addAll(vals.filter { it.isNotEmpty() })
            }
        }
        return features.distinct()
    }

    private fun extractPartNames(text: String): List<String> {
        val parts = mutableListOf<String>()
        text.lines().forEach { line ->
            if (line.trimStart().startsWith("CPU part")) {
                val v = line.substringAfter(":").trim()
                if (v.isNotEmpty()) parts.add(v)
            }
        }
        return parts.distinct()
    }

    private fun calculateSuggestedThreads(cores: Int, isBigLittle: Boolean): Int {
        if (isBigLittle) return (cores * 0.75f).toInt().coerceIn(2, 12)
        return (cores * 0.85f).toInt().coerceIn(2, 16)
    }

    private fun calculateSuggestedGpuLayers(): Int {
        val mem = vramBytes() shr 30
        return when {
            mem >= 8 -> 99
            mem >= 4 -> 33
            mem >= 2 -> 16
            else -> 0
        }
    }

    private fun vulkanAvailable(): Boolean {
        return try {
            Class.forName("android.os.Build\$VERSION")
            Build.VERSION.SDK_INT >= 29
        } catch (_: Exception) { false }
    }

    private object SystemProperties {
        private val clazz by lazy {
            try { Class.forName("android.os.SystemProperties") } catch (_: Exception) { null }
        }
        private val getMethod by lazy {
            clazz?.getMethod("get", String::class.java, String::class.java)
        }

        fun get(key: String, default: String): String {
            return try {
                getMethod?.invoke(null, key, default) as? String ?: default
            } catch (_: Exception) { default }
        }
    }
}
