package ru.koalexse.aichallenge.agent.context.summary

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Данные для сериализации summaries в JSON
 */
private data class SummaryStorageData(
    val version: Int = CURRENT_VERSION,
    val summaries: List<ConversationSummary> = emptyList()
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

/**
 * JSON-реализация хранилища summaries
 * 
 * Хранит summaries в JSON-файле в приватной директории приложения.
 * Все операции потокобезопасны благодаря использованию Mutex.
 * 
 * Особенности:
 * - Данные кэшируются в памяти для быстрого чтения
 * - Запись на диск происходит асинхронно для неблокирующего добавления
 * - При первом обращении данные загружаются с диска
 * 
 * @param context контекст приложения
 * @param fileName имя файла для хранения (по умолчанию "summaries.json")
 */
class JsonSummaryStorage(
    private val context: Context,
    private val fileName: String = "summaries.json"
) : SummaryStorage {
    
    private val mutex = Mutex()
    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .create()
    
    private val file: File
        get() = File(context.filesDir, fileName)
    
    // Кэш данных в памяти для быстрого доступа
    private var cachedSummaries: MutableList<ConversationSummary>? = null
    
    // Scope для асинхронной записи
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    override suspend fun getSummaries(): List<ConversationSummary> {
        return mutex.withLock {
            ensureLoadedInternal()
            cachedSummaries?.toList() ?: emptyList()
        }
    }
    
    override suspend fun addSummary(summary: ConversationSummary) {
        mutex.withLock {
            ensureLoadedInternal()
            if (cachedSummaries == null) {
                cachedSummaries = mutableListOf()
            }
            cachedSummaries!!.add(summary)
        }
        // Асинхронно сохраняем на диск
        scheduleWrite()
    }
    
    override suspend fun clear() {
        mutex.withLock {
            cachedSummaries?.clear()
            cachedSummaries = mutableListOf()
        }
        // Асинхронно сохраняем на диск
        scheduleWrite()
    }
    
    override suspend fun getSize(): Int {
        return mutex.withLock {
            ensureLoadedInternal()
            cachedSummaries?.size ?: 0
        }
    }
    
    override suspend fun isEmpty(): Boolean {
        return mutex.withLock {
            ensureLoadedInternal()
            cachedSummaries?.isEmpty() ?: true
        }
    }
    
    override suspend fun loadSummaries(summaries: List<ConversationSummary>) {
        mutex.withLock {
            cachedSummaries = summaries.toMutableList()
        }
        // Асинхронно сохраняем на диск
        scheduleWrite()
    }
    
    /**
     * Загружает данные с диска, если ещё не загружены
     * Должен вызываться внутри mutex.withLock
     */
    private suspend fun ensureLoadedInternal() {
        if (cachedSummaries != null) return
        
        withContext(Dispatchers.IO) {
            try {
                if (file.exists()) {
                    val json = file.readText()
                    val data = gson.fromJson(json, SummaryStorageData::class.java)
                    cachedSummaries = data?.summaries?.toMutableList() ?: mutableListOf()
                } else {
                    cachedSummaries = mutableListOf()
                }
            } catch (e: Exception) {
                // В случае ошибки парсинга начинаем с пустого списка
                cachedSummaries = mutableListOf()
            }
        }
    }
    
    /**
     * Планирует асинхронную запись на диск
     */
    private fun scheduleWrite() {
        writeScope.launch {
            saveToDisk()
        }
    }
    
    /**
     * Сохраняет данные на диск
     */
    private suspend fun saveToDisk() {
        val summariesToSave = mutex.withLock {
            cachedSummaries?.toList() ?: emptyList()
        }
        
        withContext(Dispatchers.IO) {
            try {
                val data = SummaryStorageData(summaries = summariesToSave)
                val json = gson.toJson(data)
                file.writeText(json)
            } catch (e: Exception) {
                // Логируем ошибку (в реальном приложении)
                // Не бросаем исключение, чтобы не ломать UX
            }
        }
    }
    
    /**
     * Принудительно сохраняет данные на диск (асинхронно)
     */
    suspend fun flush() {
        saveToDisk()
    }
}
