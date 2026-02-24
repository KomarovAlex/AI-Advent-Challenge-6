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
 * JSON-реализация репозитория истории чата
 * 
 * Хранит все сессии в одном JSON-файле в приватной директории приложения.
 * Все операции потокобезопасны благодаря использованию Mutex.
 * 
 * @param context контекст приложения
 * @param fileName имя файла для хранения (по умолчанию "chat_history.json")
 */
class JsonChatHistoryRepository(
    private val context: Context,
    private val fileName: String = "chat_history.json"
) : ChatHistoryRepository {
    
    private val mutex = Mutex()
    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .create()
    
    private val file: File
        get() = File(context.filesDir, fileName)
    
    // Кэш данных в памяти для быстрого доступа
    private var cachedData: ChatHistoryData? = null
    
    override suspend fun saveSession(session: ChatSession) {
        mutex.withLock {
            val data = loadDataInternal()
            val updatedSessions = data.sessions
                .filter { it.id != session.id }
                .plus(session)
            
            val updatedData = data.copy(sessions = updatedSessions)
            saveDataInternal(updatedData)
        }
    }
    
    override suspend fun loadSession(sessionId: String): ChatSession? {
        return mutex.withLock {
            val data = loadDataInternal()
            data.sessions.find { it.id == sessionId }
        }
    }
    
    override suspend fun loadLatestSession(): ChatSession? {
        return mutex.withLock {
            val data = loadDataInternal()
            data.sessions.maxByOrNull { it.updatedAt }
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
            val updatedData = data.copy(activeSessionId = sessionId)
            saveDataInternal(updatedData)
        }
    }
    
    override suspend fun deleteSession(sessionId: String) {
        mutex.withLock {
            val data = loadDataInternal()
            val updatedSessions = data.sessions.filter { it.id != sessionId }
            
            // Если удаляем активную сессию, сбрасываем activeSessionId
            val newActiveId = if (data.activeSessionId == sessionId) null else data.activeSessionId
            
            val updatedData = data.copy(
                sessions = updatedSessions,
                activeSessionId = newActiveId
            )
            saveDataInternal(updatedData)
        }
    }
    
    override suspend fun getAllSessions(): List<ChatSession> {
        return mutex.withLock {
            val data = loadDataInternal()
            data.sessions.sortedByDescending { it.updatedAt }
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
    
    /**
     * Загружает данные из файла (внутренний метод, без блокировки)
     * Должен вызываться внутри mutex.withLock
     */
    private suspend fun loadDataInternal(): ChatHistoryData {
        // Возвращаем кэш, если есть
        cachedData?.let { return it }
        
        return withContext(Dispatchers.IO) {
            try {
                if (file.exists()) {
                    val json = file.readText()
                    gson.fromJson(json, ChatHistoryData::class.java)
                        ?: ChatHistoryData()
                } else {
                    ChatHistoryData()
                }
            } catch (e: Exception) {
                // В случае ошибки парсинга возвращаем пустые данные
                // и логируем ошибку (в реальном приложении)
                ChatHistoryData()
            }
        }.also { cachedData = it }
    }
    
    /**
     * Сохраняет данные в файл (внутренний метод, без блокировки)
     * Должен вызываться внутри mutex.withLock
     */
    private suspend fun saveDataInternal(data: ChatHistoryData) {
        cachedData = data
        
        withContext(Dispatchers.IO) {
            try {
                val json = gson.toJson(data)
                file.writeText(json)
            } catch (e: Exception) {
                // Логируем ошибку (в реальном приложении)
                // Не бросаем исключение, чтобы не ломать UX
            }
        }
    }
}
