package com.gguf.ipc

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ModelManager — Tracks all downloaded/cached models with metadata.
 * Persists model list via SharedPreferences AND scans files directory.
 */
object ModelManager {

    private const val PREFS_NAME = "gguf_models"
    private const val KEY_MODELS = "models_json"

    data class Model(
        val id: String,
        val name: String,
        val path: String,
        val format: String,
        val engine: InferenceEngine.EngineType,
        val sizeBytes: Long = 0,
        val addedAt: Long = System.currentTimeMillis(),
        val lastUsed: Long = 0
    )

    private var modelsDir: File? = null
    private val _models = mutableListOf<Model>()
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        modelsDir = File(context.filesDir, "models").apply { mkdirs() }
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadFromPrefs()
        scanAndLoadModels(context)
        saveToPrefs()
    }

    private fun loadFromPrefs() {
        val json = prefs?.getString(KEY_MODELS, null) ?: return
        try {
            val arr = JSONArray(json)
            _models.clear()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                _models.add(Model(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    path = obj.getString("path"),
                    format = obj.getString("format"),
                    engine = InferenceEngine.EngineType.valueOf(obj.getString("engine")),
                    sizeBytes = obj.optLong("sizeBytes", 0),
                    addedAt = obj.optLong("addedAt", System.currentTimeMillis()),
                    lastUsed = obj.optLong("lastUsed", 0)
                ))
            }
        } catch (e: Exception) {
            android.util.Log.e("ModelManager", "Failed to load models from prefs", e)
        }
    }

    private fun saveToPrefs() {
        try {
            val arr = JSONArray()
            for (m in _models) {
                arr.put(JSONObject().apply {
                    put("id", m.id)
                    put("name", m.name)
                    put("path", m.path)
                    put("format", m.format)
                    put("engine", m.engine.name)
                    put("sizeBytes", m.sizeBytes)
                    put("addedAt", m.addedAt)
                    put("lastUsed", m.lastUsed)
                })
            }
            prefs?.edit()?.putString(KEY_MODELS, arr.toString())?.apply()
        } catch (e: Exception) {
            android.util.Log.e("ModelManager", "Failed to save models to prefs", e)
        }
    }

    private fun scanAndLoadModels(context: Context) {
        val dir = modelsDir ?: return
        if (!dir.exists()) return

        for (file in dir.listFiles() ?: emptyArray()) {
            val ext = when {
                file.isDirectory && File(file, "config.json").exists() -> "mnn"
                file.isFile -> file.extension.lowercase()
                else -> continue
            }
            if (ext !in setOf("gguf", "mnn", "tflite", "litertlm")) continue

            // Skip if already in list (from prefs)
            if (_models.any { it.path == file.absolutePath }) continue

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
                sizeBytes = if (file.isFile) file.length() else file.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
            ))
        }
    }

    fun getModels(): List<Model> = _models.sortedByDescending { it.lastUsed }

    fun getModel(id: String): Model? = _models.find { it.id == id }

    fun addModel(model: Model) {
        // Remove existing model with same path or name
        _models.removeAll { it.path == model.path || it.name == model.name }
        _models.add(0, model.copy(lastUsed = System.currentTimeMillis()))
        saveToPrefs()
    }

    fun deleteModel(id: String) {
        val model = _models.find { it.id == id } ?: return
        try {
            val file = File(model.path)
            if (file.isDirectory) {
                file.deleteRecursively()
            } else {
                file.delete()
            }
        } catch (_: Exception) {}
        _models.removeAll { it.id == id }
        saveToPrefs()
    }

    fun markUsed(id: String) {
        val idx = _models.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val m = _models[idx]
            _models[idx] = m.copy(lastUsed = System.currentTimeMillis())
            saveToPrefs()
        }
    }

    fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
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
