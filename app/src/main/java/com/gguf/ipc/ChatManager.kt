package com.gguf.ipc

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * ChatManager — Manages multiple chat sessions with JSON file persistence.
 * Each session is stored as a separate JSON file in the sessions/ directory.
 */
object ChatManager {

    data class ChatSession(
        val id: String,
        var name: String,
        val createdAt: Long,
        var lastMessageAt: Long,
        var messageCount: Int = 0
    )

    data class Message(
        val role: String,  // "user" or "assistant"
        val content: String,
        val timestamp: Long = System.currentTimeMillis(),
        val tokensPerSec: Float = 0f,
        val tokenCount: Int = 0
    )

    private var sessionsDir: File? = null
    private var currentSessionId: String? = null

    fun init(context: Context) {
        sessionsDir = File(context.filesDir, "sessions").also { it.mkdirs() }
    }

    fun createSession(name: String? = null): ChatSession {
        val id = "session_${System.currentTimeMillis()}"
        val sessionName = name ?: generateDefaultName()
        val session = ChatSession(
            id = id,
            name = sessionName,
            createdAt = System.currentTimeMillis(),
            lastMessageAt = System.currentTimeMillis()
        )
        saveSessionMeta(session)
        currentSessionId = id
        return session
    }

    fun getCurrentSessionId(): String? = currentSessionId

    fun setCurrentSession(sessionId: String) {
        currentSessionId = sessionId
    }

    fun getSessions(): List<ChatSession> {
        val dir = sessionsDir ?: return emptyList()
        return dir.listFiles()
            ?.filter { it.name.endsWith("_meta.json") }
            ?.mapNotNull { loadSessionMeta(it) }
            ?.sortedByDescending { it.lastMessageAt }
            ?: emptyList()
    }

    fun addMessage(sessionId: String, message: Message) {
        val dir = sessionsDir ?: return
        val messagesFile = File(dir, "${sessionId}_messages.json")

        val messages = loadMessages(sessionId).toMutableList()
        messages.add(message)
        saveMessages(sessionId, messages)

        // Update metadata
        val metaFile = File(dir, "${sessionId}_meta.json")
        if (metaFile.exists()) {
            try {
                val json = JSONObject(metaFile.readText())
                json.put("lastMessageAt", message.timestamp)
                json.put("messageCount", messages.size)
                metaFile.writeText(json.toString())
            } catch (_: Exception) {}
        }
    }

    fun getMessages(sessionId: String): List<Message> {
        return loadMessages(sessionId)
    }

    fun renameSession(sessionId: String, newName: String) {
        val dir = sessionsDir ?: return
        val metaFile = File(dir, "${sessionId}_meta.json")
        if (metaFile.exists()) {
            try {
                val json = JSONObject(metaFile.readText())
                json.put("name", newName)
                metaFile.writeText(json.toString())
            } catch (_: Exception) {}
        }
    }

    fun deleteSession(sessionId: String) {
        val dir = sessionsDir ?: return
        File(dir, "${sessionId}_meta.json").delete()
        File(dir, "${sessionId}_messages.json").delete()
        if (currentSessionId == sessionId) {
            currentSessionId = null
        }
    }

    fun exportSession(sessionId: String): String {
        val messages = loadMessages(sessionId)
        val session = getSessions().find { it.id == sessionId }

        val sb = StringBuilder()
        sb.appendLine("=== GGUF ZeroCopy Chat Export ===")
        sb.appendLine("Session: ${session?.name ?: sessionId}")
        sb.appendLine("Messages: ${messages.size}")
        sb.appendLine()

        for ((i, msg) in messages.withIndex()) {
            val ts = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp))
            sb.appendLine("[$ts] ${msg.role.uppercase()}:")
            sb.appendLine(msg.content)
            sb.appendLine()
        }

        return sb.toString()
    }

    private fun loadMessages(sessionId: String): List<Message> {
        val dir = sessionsDir ?: return emptyList()
        val file = File(dir, "${sessionId}_messages.json")
        if (!file.exists()) return emptyList()

        return try {
            val json = JSONArray(file.readText())
            (0 until json.length()).map { i ->
                val obj = json.getJSONObject(i)
                Message(
                    role = obj.getString("role"),
                    content = obj.getString("content"),
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                    tokensPerSec = obj.optDouble("tokensPerSec", 0.0).toFloat(),
                    tokenCount = obj.optInt("tokenCount", 0)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveMessages(sessionId: String, messages: List<Message>) {
        val dir = sessionsDir ?: return
        val file = File(dir, "${sessionId}_messages.json")

        val json = JSONArray()
        for (msg in messages) {
            json.put(JSONObject().apply {
                put("role", msg.role)
                put("content", msg.content)
                put("timestamp", msg.timestamp)
                put("tokensPerSec", msg.tokensPerSec)
                put("tokenCount", msg.tokenCount)
            })
        }
        file.writeText(json.toString())
    }

    private fun saveSessionMeta(session: ChatSession) {
        val dir = sessionsDir ?: return
        val file = File(dir, "${session.id}_meta.json")
        file.writeText(JSONObject().apply {
            put("id", session.id)
            put("name", session.name)
            put("createdAt", session.createdAt)
            put("lastMessageAt", session.lastMessageAt)
            put("messageCount", session.messageCount)
        }.toString())
    }

    private fun loadSessionMeta(file: File): ChatSession? {
        return try {
            val json = JSONObject(file.readText())
            ChatSession(
                id = json.getString("id"),
                name = json.getString("name"),
                createdAt = json.getLong("createdAt"),
                lastMessageAt = json.getLong("lastMessageAt"),
                messageCount = json.optInt("messageCount", 0)
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun generateDefaultName(): String {
        val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
        return "Chat ${sdf.format(Date())}"
    }
}
