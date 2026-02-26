package ru.koalexse.aichallenge.data.persistence

/**
 * Модель сообщения для сериализации в JSON
 */
data class PersistedAgentMessage(
    val role: String,
    val content: String,
    val timestamp: Long
)

/**
 * Модель статистики токенов сессии для сериализации
 */
data class PersistedSessionStats(
    val totalPromptTokens: Int = 0,
    val totalCompletionTokens: Int = 0,
    val totalTokens: Int = 0,
    val messageCount: Int = 0
)

/**
 * Модель summary для сериализации в JSON
 * 
 * @param content текст summary
 * @param originalMessageCount количество сообщений, из которых создано summary
 * @param createdAt время создания summary
 */
data class PersistedSummary(
    val content: String,
    val originalMessageCount: Int,
    val createdAt: Long
)

/**
 * Модель сессии чата для сериализации
 * 
 * @param id уникальный идентификатор сессии
 * @param messages список сообщений
 * @param createdAt время создания сессии
 * @param updatedAt время последнего обновления
 * @param model модель, использованная в сессии
 * @param sessionStats накопительная статистика токенов за сессию
 * @param summaries сохранённые summaries для компрессии истории
 */
data class ChatSession(
    val id: String,
    val messages: List<PersistedAgentMessage>,
    val createdAt: Long,
    val updatedAt: Long,
    val model: String? = null,
    val sessionStats: PersistedSessionStats? = null,
    val summaries: List<PersistedSummary> = emptyList()
)

/**
 * Контейнер для хранения всех сессий
 */
data class ChatHistoryData(
    val version: Int = 2, // Увеличена версия для поддержки summaries
    val sessions: List<ChatSession> = emptyList(),
    val activeSessionId: String? = null
)
