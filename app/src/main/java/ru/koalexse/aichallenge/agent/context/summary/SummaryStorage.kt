package ru.koalexse.aichallenge.agent.context.summary

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Хранилище для summary сжатых блоков сообщений
 * 
 * Позволяет сохранять и извлекать summaries независимо от основной истории.
 * Все методы suspend для корректной работы с IO и синхронизацией.
 */
interface SummaryStorage {
    
    /**
     * Возвращает все сохранённые summaries в порядке создания
     */
    suspend fun getSummaries(): List<ConversationSummary>
    
    /**
     * Добавляет новое summary
     */
    suspend fun addSummary(summary: ConversationSummary)
    
    /**
     * Очищает все summaries
     */
    suspend fun clear()
    
    /**
     * Возвращает количество сохранённых summaries
     */
    suspend fun getSize(): Int
    
    /**
     * Проверяет, пусто ли хранилище
     */
    suspend fun isEmpty(): Boolean
    
    /**
     * Загружает summaries из списка (при восстановлении сессии)
     * 
     * Очищает текущие summaries и заменяет их переданным списком.
     * 
     * @param summaries список summaries для загрузки
     */
    suspend fun loadSummaries(summaries: List<ConversationSummary>)
}

/**
 * In-memory реализация хранилища summaries
 * 
 * Хранит summaries в памяти. Не сохраняется между перезапусками.
 * Потокобезопасна благодаря Mutex.
 */
class InMemorySummaryStorage : SummaryStorage {
    
    private val mutex = Mutex()
    private val summaries = mutableListOf<ConversationSummary>()
    
    override suspend fun getSummaries(): List<ConversationSummary> {
        return mutex.withLock {
            summaries.toList()
        }
    }
    
    override suspend fun addSummary(summary: ConversationSummary) {
        mutex.withLock {
            summaries.add(summary)
        }
    }
    
    override suspend fun clear() {
        mutex.withLock {
            summaries.clear()
        }
    }
    
    override suspend fun getSize(): Int {
        return mutex.withLock { 
            summaries.size 
        }
    }
    
    override suspend fun isEmpty(): Boolean {
        return mutex.withLock { 
            summaries.isEmpty() 
        }
    }
    
    override suspend fun loadSummaries(summaries: List<ConversationSummary>) {
        mutex.withLock {
            this.summaries.clear()
            this.summaries.addAll(summaries)
        }
    }
}
