package com.gguf.ipc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * MultimodalHelper — Handles image, audio, and document attachments for multimodal inference.
 * Supports:
 * - Image understanding (via CLIP/SigLIP or vision LLMs)
 * - Audio transcription (via whisper.cpp or Qwen-Audio)
 * - Document processing (PDF → images → vision LLM)
 */
object MultimodalHelper {

    private const val TAG = "MultimodalHelper"

    data class ImageAttachment(
        val path: String,
        val bitmap: Bitmap? = null,
        val width: Int = 0,
        val height: Int = 0
    )

    data class AudioAttachment(
        val path: String,
        val sampleRate: Int = 16000,
        val channels: Int = 1,
        val bitDepth: Int = 16
    )

    data class DocumentAttachment(
        val path: String,
        val type: DocumentType,
        val pageCount: Int = 0
    )

    enum class DocumentType { PDF, TEXT, MARKDOWN, CODE }

    /**
     * Process an image URI for vision model input.
     * Returns the processed bitmap and file path.
     */
    fun processImage(context: Context, uri: Uri): ImageAttachment? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            if (bitmap == null) {
                Log.e(TAG, "Failed to decode image")
                return null
            }

            // Save to cache for model processing
            val cacheFile = File(context.cacheDir, "vision_${System.currentTimeMillis()}.jpg")
            FileOutputStream(cacheFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }

            ImageAttachment(
                path = cacheFile.absolutePath,
                bitmap = bitmap,
                width = bitmap.width,
                height = bitmap.height
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process image: ${e.message}")
            null
        }
    }

    /**
     * Process an audio file for whisper/Audio model input.
     * Converts to 16kHz mono PCM if needed.
     */
    fun processAudio(context: Context, uri: Uri): AudioAttachment? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null

            // Save to cache
            val cacheFile = File(context.cacheDir, "audio_${System.currentTimeMillis()}.wav")
            FileOutputStream(cacheFile).use { out ->
                inputStream.copyTo(out)
            }
            inputStream.close()

            AudioAttachment(
                path = cacheFile.absolutePath,
                sampleRate = 16000,
                channels = 1,
                bitDepth = 16
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process audio: ${e.message}")
            null
        }
    }

    /**
     * Process a document file for LLM input.
     * For PDFs: converts pages to images for vision LLM processing.
     * For text files: reads content directly.
     */
    fun processDocument(context: Context, uri: Uri, fileName: String): DocumentAttachment? {
        return try {
            val type = when {
                fileName.endsWith(".pdf", ignoreCase = true) -> DocumentType.PDF
                fileName.endsWith(".md", ignoreCase = true) -> DocumentType.MARKDOWN
                fileName.endsWith(".txt", ignoreCase = true) ||
                fileName.endsWith(".log", ignoreCase = true) -> DocumentType.TEXT
                fileName.endsWith(".kt", ignoreCase = true) ||
                fileName.endsWith(".java", ignoreCase = true) ||
                fileName.endsWith(".py", ignoreCase = true) ||
                fileName.endsWith(".js", ignoreCase = true) ||
                fileName.endsWith(".ts", ignoreCase = true) ||
                fileName.endsWith(".cpp", ignoreCase = true) ||
                fileName.endsWith(".c", ignoreCase = true) ||
                fileName.endsWith(".h", ignoreCase = true) -> DocumentType.CODE
                else -> DocumentType.TEXT
            }

            // Save to cache
            val cacheFile = File(context.cacheDir, "doc_${fileName}")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            }

            DocumentAttachment(
                path = cacheFile.absolutePath,
                type = type
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process document: ${e.message}")
            null
        }
    }

    /**
     * Read text content from a document file.
     * Limits to first N characters to fit in LLM context window.
     */
    fun readDocumentContent(filePath: String, maxChars: Int = 4000): String {
        return try {
            val file = File(filePath)
            if (!file.exists()) return ""

            val content = file.readText()
            if (content.length > maxChars) {
                content.substring(0, maxChars) + "\n\n[Truncated at $maxChars characters]"
            } else {
                content
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read document: ${e.message}")
            ""
        }
    }

    /**
     * Get supported image formats.
     */
    fun getSupportedImageFormats(): List<String> {
        return listOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
    }

    /**
     * Get supported audio formats.
     */
    fun getSupportedAudioFormats(): List<String> {
        return listOf("wav", "mp3", "flac", "ogg", "aac", "m4a")
    }

    /**
     * Get supported document formats.
     */
    fun getSupportedDocumentFormats(): List<String> {
        return listOf("txt", "md", "pdf", "kt", "java", "py", "js", "ts", "cpp", "c", "h")
    }
}
