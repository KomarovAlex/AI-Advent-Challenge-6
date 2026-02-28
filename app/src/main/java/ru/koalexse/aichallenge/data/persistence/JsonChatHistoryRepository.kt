package ru.koalexse.aichallenge.data.persistence

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * JSON-реализация [ChatHistoryRepository].
 *
 * Хранит все сессии в одном JSON-файле в приватной директории приложения.
 * Все операции потокобезопасны благодаря Mutex.
 *
 * @param context контекст приложения
 * @param fileName имя файла для хранения (по умолчанию "chat_history.json")
 */
class JsonChatHistoryRepository(
    private val context: Context,
    private val fileName: String = "chat_history.json"
) : ChatHistoryRepository {

    private val mutex = Mutex()
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private val file: File
        get() = File(context.filesDir, fileName)

    private var cachedData: ChatHistoryData? = null

    override suspend fun saveSession(session: ChatSession) {
        mutex.withLock {
            val data = loadDataInternal()
            val updatedData = data.copy(
                sessions = data.sessions.filter { it.id != session.id } + session
            )
            saveDataInternal(updatedData)
        }
    }

    override suspend fun loadSession(sessionId: String): ChatSession? {
        return mutex.withLock {
            loadDataInternal().sessions.find { it.id == sessionId }
        }
    }

    override suspend fun loadLatestSession(): ChatSession? {
        return mutex.withLock {
            loadDataInternal().sessions.maxByOrNull { it.updatedAt }
        }
    }

    override suspend fun loadActiveSession(): ChatSession? {
        return mutex.withLock {
            val data = loadDataInternal()
            val activeId = data.activeSessionId
            if (activeId != null) {
                data.sessions.find { it.id == activeId }
            } else {
                data.sessions.maxByOrNull { it.updatedAt }
            }
        }
    }

    override suspend fun setActiveSession(sessionId: String) {
        mutex.withLock {
            val data = loadDataInternal()
            saveDataInternal(data.copy(activeSessionId = sessionId))
        }
    }

    override suspend fun deleteSession(sessionId: String) {
        mutex.withLock {
            val data = loadDataInternal()
            val newActiveId = if (data.activeSessionId == sessionId) null else data.activeSessionId
            saveDataInternal(
                data.copy(
                    sessions = data.sessions.filter { it.id != sessionId },
                    activeSessionId = newActiveId
                )
            )
        }
    }

    override suspend fun getAllSessions(): List<ChatSession> {
        return mutex.withLock {
            loadDataInternal().sessions.sortedByDescending { it.updatedAt }
        }
    }

    override suspend fun clearAll() {
        mutex.withLock {
            saveDataInternal(ChatHistoryData())
        }
    }

    override suspend fun getActiveSessionId(): String? {
        return mutex.withLock {
            loadDataInternal().activeSessionId
        }
    }

    private suspend fun loadDataInternal(): ChatHistoryData {
        cachedData?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                if (file.exists()) {
                    gson.fromJson(file.readText(), ChatHistoryData::class.java) ?: ChatHistoryData()
                } else {
                    ChatHistoryData()
                }
            } catch (e: Exception) {
                ChatHistoryData()
            }
        }.also { cachedData = it }
    }

    private suspend fun saveDataInternal(data: ChatHistoryData) {
        cachedData = data
        withContext(Dispatchers.IO) {
            try {
                file.writeText(gson.toJson(data))
            } catch (e: Exception) {
                // не ломаем UX при ошибке записи
            }
        }
    }
}
