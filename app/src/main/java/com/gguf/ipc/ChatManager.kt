package com.gguf.ipc

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "New Chat",
    val messages: List<ChatMessage> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun withMessages(newMessages: List<ChatMessage>): ChatSession =
        copy(messages = newMessages, updatedAt = System.currentTimeMillis())

    fun autoName(): String {
        val firstUser = messages.firstOrNull { it.role == Role.USER }?.content?.take(48)?.trim()
        if (!firstUser.isNullOrEmpty()) return firstUser
        val df = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
        return "Chat ${df.format(Date(createdAt))}"
    }
}

class ChatManager(private val context: Context) {
    private val sessionsDir = File(context.filesDir, "sessions").also { it.mkdirs() }
    private val indexPath = File(sessionsDir, "index.json")

    private var sessions: MutableList<ChatSession> = mutableListOf()
    private var currentIndex: Int = -1

    init { loadIndex() }

    // ── Accessors ──

    val currentSession: ChatSession?
        get() = if (currentIndex in sessions.indices) sessions[currentIndex] else null

    val currentSessionId: String?
        get() = currentSession?.id

    val allSessions: List<ChatSession>
        get() = sessions.toList()

    val currentSessionIndex: Int
        get() = currentIndex

    // ── Session CRUD ──

    fun createSession(): ChatSession {
        val session = ChatSession()
        sessions.add(session)
        currentIndex = sessions.size - 1
        saveIndex()
        return session
    }

    fun switchTo(id: String): Boolean {
        val idx = sessions.indexOfFirst { it.id == id }
        if (idx < 0) return false
        currentIndex = idx
        val loaded = loadSession(sessions[idx].id)
        if (loaded != null) sessions[idx] = loaded
        saveIndex()
        return true
    }

    fun switchToIndex(idx: Int): Boolean {
        if (idx !in sessions.indices) return false
        currentIndex = idx
        val loaded = loadSession(sessions[idx].id)
        if (loaded != null) sessions[idx] = loaded
        saveIndex()
        return true
    }

    fun addMessage(msg: ChatMessage) {
        val idx = currentIndex
        if (idx !in sessions.indices) return
        val updated = sessions[idx].withMessages(sessions[idx].messages + msg)
        sessions[idx] = updated
        persistSession(updated)
        saveIndex()
    }

    fun updateLastMessage(msg: ChatMessage) {
        val idx = currentIndex
        if (idx !in sessions.indices) return
        val msgs = sessions[idx].messages.toMutableList()
        if (msgs.isNotEmpty()) msgs[msgs.lastIndex] = msg
        val updated = sessions[idx].withMessages(msgs)
        sessions[idx] = updated
        persistSession(updated)
        saveIndex()
    }

    fun renameSession(id: String, name: String) {
        val idx = sessions.indexOfFirst { it.id == id }
        if (idx < 0) return
        sessions[idx] = sessions[idx].copy(name = name, updatedAt = System.currentTimeMillis())
        saveIndex()
    }

    fun deleteSession(id: String) {
        val idx = sessions.indexOfFirst { it.id == id }
        if (idx < 0) return
        sessions.removeAt(idx)
        File(sessionsDir, "$id.json").delete()
        if (currentIndex >= sessions.size) currentIndex = sessions.size - 1
        if (sessions.isEmpty()) createSession()
        saveIndex()
    }

    fun clearCurrentSession() {
        val idx = currentIndex
        if (idx !in sessions.indices) return
        val updated = sessions[idx].copy(messages = emptyList(), name = "New Chat",
            updatedAt = System.currentTimeMillis())
        sessions[idx] = updated
        persistSession(updated)
        saveIndex()
    }

    fun loadMessagesForCurrent(): List<ChatMessage> {
        val session = currentSession ?: return emptyList()
        val loaded = loadSession(session.id)
        if (loaded != null && currentIndex in sessions.indices) {
            sessions[currentIndex] = loaded
        }
        return currentSession?.messages ?: emptyList()
    }

    // ── Persistence ──

    private fun loadIndex() {
        try {
            if (!indexPath.exists()) {
                createSession()
                return
            }
            val json = JSONArray(indexPath.readText())
            sessions.clear()
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                sessions.add(ChatSession(
                    id = obj.getString("id"),
                    name = obj.optString("name", "New Chat"),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
                ))
            }
            currentIndex = if (sessions.isEmpty()) { createSession(); 0 } else 0
        } catch (_: Exception) {
            if (sessions.isEmpty()) createSession()
        }
    }

    private fun saveIndex() {
        try {
            val arr = JSONArray()
            sessions.forEach { s ->
                arr.put(JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("createdAt", s.createdAt)
                    put("updatedAt", s.updatedAt)
                })
            }
            indexPath.writeText(arr.toString(2))
        } catch (_: Exception) {}
    }

    private fun persistSession(session: ChatSession) {
        try {
            val arr = JSONArray()
            session.messages.forEach { msg ->
                arr.put(JSONObject().apply {
                    put("role", msg.role.name)
                    put("content", msg.content)
                    put("timestamp", msg.timestamp)
                    put("tokensPerSec", msg.tokensPerSec.toDouble())
                    put("tokenCount", msg.tokenCount)
                })
            }
            File(sessionsDir, "${session.id}.json").writeText(arr.toString(2))
        } catch (_: Exception) {}
    }

    private fun loadSession(id: String): ChatSession? {
        val file = File(sessionsDir, "$id.json")
        if (!file.exists()) return null
        return try {
            val arr = JSONArray(file.readText())
            val msgs = mutableListOf<ChatMessage>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val role = try { Role.valueOf(obj.getString("role")) } catch (_: Exception) { Role.USER }
                msgs.add(ChatMessage(
                    role = role,
                    content = obj.optString("content", ""),
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                    tokensPerSec = obj.optDouble("tokensPerSec", 0.0).toFloat(),
                    tokenCount = obj.optInt("tokenCount", 0)
                ))
            }
            sessions.find { it.id == id }?.copy(messages = msgs)
        } catch (_: Exception) { null }
    }
}
