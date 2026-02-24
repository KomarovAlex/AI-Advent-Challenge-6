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
 * Модель сессии чата для сериализации
 * 
 * @param id уникальный идентификатор сессии
 * @param messages список сообщений
 * @param createdAt время создания сессии
 * @param updatedAt время последнего обновления
 * @param model модель, использованная в сессии
 */
data class ChatSession(
    val id: String,
    val messages: List<PersistedAgentMessage>,
    val createdAt: Long,
    val updatedAt: Long,
    val model: String? = null
)

/**
 * Контейнер для хранения всех сессий
 */
data class ChatHistoryData(
    val version: Int = 1,
    val sessions: List<ChatSession> = emptyList(),
    val activeSessionId: String? = null
)
