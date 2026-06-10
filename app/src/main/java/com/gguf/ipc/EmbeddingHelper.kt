package com.gguf.ipc

import android.util.Log
import org.json.JSONObject

/**
 * EmbeddingHelper — Handles text embedding models for RAG/search applications.
 * Supports embedding models via llama.cpp (GGUF format).
 *
 * Supported models:
 * - nomic-embed-text v1.5 (274MB, 768 dims)
 * - all-minilm-L6-v2 (46MB, 384 dims)
 * - Qwen3-Embedding-0.6B (400MB, 32-4096 dims)
 * - BGE-M3 (1.2GB, 1024 dims)
 */
object EmbeddingHelper {

    private const val TAG = "EmbeddingHelper"

    data class EmbeddingResult(
        val embeddings: List<Float>,
        val dimensions: Int,
        val model: String,
        val processingTimeMs: Long
    )

    data class EmbeddingModel(
        val name: String,
        val dimensions: Int,
        val sizeMB: Int,
        val contextLength: Int,
        val description: String
    )

    /**
     * Available embedding models optimized for mobile.
     */
    val availableModels = listOf(
        EmbeddingModel(
            name = "all-minilm-L6-v2",
            dimensions = 384,
            sizeMB = 46,
            contextLength = 256,
            description = "Ultra-light, fastest. Best for simple search."
        ),
        EmbeddingModel(
            name = "nomic-embed-text-v1.5",
            dimensions = 768,
            sizeMB = 274,
            contextLength = 8192,
            description = "Most popular, Matryoshka support (512/256/128/64 dims)."
        ),
        EmbeddingModel(
            name = "Qwen3-Embedding-0.6B",
            dimensions = 4096,
            sizeMB = 400,
            contextLength = 8192,
            description = "Multilingual, code search, variable dimensions."
        ),
        EmbeddingModel(
            name = "BGE-M3",
            dimensions = 1024,
            sizeMB = 1200,
            contextLength = 8192,
            description = "Best quality multilingual retrieval."
        )
    )

    /**
     * Check if a GGUF file is an embedding model.
     */
    fun isEmbeddingModel(filePath: String): Boolean {
        val name = filePath.lowercase()
        return name.contains("embed") ||
                name.contains("minilm") ||
                name.contains("bge") ||
                name.contains("e5") ||
                name.contains("gte")
    }

    /**
     * Get embedding model recommendation based on available RAM.
     */
    fun recommendModel(availableRAMMB: Long): EmbeddingModel {
        return when {
            availableRAMMB < 200 -> availableModels[0]  // all-minilm (46MB)
            availableRAMMB < 500 -> availableModels[1]  // nomic-embed (274MB)
            availableRAMMB < 800 -> availableModels[2]  // Qwen3-Embedding (400MB)
            else -> availableModels[3]                   // BGE-M3 (1.2GB)
        }
    }

    /**
     * Generate embeddings for text using the loaded embedding model.
     * This is a placeholder - actual implementation uses llama.cpp embedding API.
     */
    fun generateEmbedding(text: String, modelPath: String): EmbeddingResult? {
        return try {
            val startTime = System.currentTimeMillis()

            // In production, this would call llama.cpp embedding API:
            // llama-embedding -m model.gguf -p "text" -e
            //
            // For now, return a placeholder
            Log.i(TAG, "Generating embedding for text (${text.length} chars)")
            Log.i(TAG, "Model: $modelPath")
            Log.i(TAG, "Note: Full embedding integration requires llama.cpp embedding API")

            val processingTime = System.currentTimeMillis() - startTime

            EmbeddingResult(
                embeddings = List(384) { 0f },  // Placeholder
                dimensions = 384,
                model = modelPath,
                processingTimeMs = processingTime
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate embedding: ${e.message}")
            null
        }
    }

    /**
     * Compute cosine similarity between two embedding vectors.
     */
    fun cosineSimilarity(a: List<Float>, b: List<Float>): Float {
        if (a.size != b.size) return 0f

        var dotProduct = 0f
        var normA = 0f
        var normB = 0f

        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }

        return if (normA > 0 && normB > 0) {
            dotProduct / (Math.sqrt(normA.toDouble()) * Math.sqrt(normB.toDouble())).toFloat()
        } else {
            0f
        }
    }

    /**
     * Rank documents by similarity to query embedding.
     */
    fun rankDocuments(
        queryEmbedding: List<Float>,
        documentEmbeddings: List<Pair<String, List<Float>>>,
        topK: Int = 5
    ): List<Pair<String, Float>> {
        return documentEmbeddings
            .map { (doc, embedding) ->
                doc to cosineSimilarity(queryEmbedding, embedding)
            }
            .sortedByDescending { it.second }
            .take(topK)
    }
}
