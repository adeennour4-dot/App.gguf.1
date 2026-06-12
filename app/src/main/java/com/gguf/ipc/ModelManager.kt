package com.gguf.ipc

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * ModelManager — Tracks all downloaded/cached models with metadata.
 * Persists model list across app restarts.
 */
object ModelManager {

    private const val PREFS_NAME = "gguf_models"

    data class Model(
        val id: String,
        val name: String,
        val path: String,
        val format: String, // "gguf", "mnn", "tflite", "litertlm"
        val engine: InferenceEngine.EngineType,
        val sizeBytes: Long = 0,
        val addedAt: Long = System.currentTimeMillis(),
        val lastUsed: Long = 0
    )

    private var modelsDir: File? = null
    private val _models = mutableListOf<Model>()

    fun init(context: Context) {
        modelsDir = File(context.filesDir, "models").apply { mkdirs() }
        scanAndLoadModels(context)
    }

    private fun scanAndLoadModels(context: Context) {
        val dir = modelsDir ?: return
        if (!dir.exists()) return

        _models.clear()
        for (file in dir.listFiles() ?: emptyArray()) {
            if (file.isFile) {
                val ext = file.extension.lowercase()
                if (ext in setOf("gguf", "mnn", "tflite", "litertlm")) {
                    val id = "${file.name}_${file.lastModified()}"
                    _models.add(Model(
                        id = id,
                        name = file.name,
                        path = file.absolutePath,
                        format = ext,
                        engine = when (ext) {
                            "gguf" -> InferenceEngine.EngineType.LLAMA_CPP
                            "mnn" -> InferenceEngine.EngineType.MNN
                            else -> InferenceEngine.EngineType.LITER_T
                        },
                        sizeBytes = file.length()
                    ))
                }
            }
        }
    }

    fun getModels(): List<Model> = _models.sortedByDescending { it.lastUsed }

    fun getModel(id: String): Model? = _models.find { it.id == id }

    fun addModel(model: Model) {
        _models.removeAll { it.path == model.path }
        _models.add(0, model.copy(lastUsed = System.currentTimeMillis()))
    }

    fun deleteModel(id: String) {
        val model = _models.find { it.id == id } ?: return
        try {
            File(model.path).delete()
        } catch (_: Exception) {}
        _models.removeAll { it.id == id }
    }

    fun markUsed(id: String) {
        val idx = _models.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val m = _models[idx]
            _models[idx] = m.copy(lastUsed = System.currentTimeMillis())
        }
    }

    fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    fun getEngineIcon(format: String): String {
        return when (format.lowercase()) {
            "gguf" -> "🦙"
            "mnn" -> "⚡"
            "tflite", "litertlm" -> "🔷"
            else -> "❓"
        }
    }
}