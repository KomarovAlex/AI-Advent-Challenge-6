package ru.koalexse.aichallenge.data.persistence

import ru.koalexse.aichallenge.agent.AgentMessage
import ru.koalexse.aichallenge.agent.Role
import java.util.UUID

/**
 * Маппер для конвертации между доменными моделями и моделями для сериализации
 */
object ChatHistoryMapper {
    
    /**
     * Конвертирует AgentMessage в PersistedAgentMessage
     */
    fun AgentMessage.toPersisted(): PersistedAgentMessage {
        return PersistedAgentMessage(
            role = role.name,
            content = content,
            timestamp = timestamp
        )
    }
    
    /**
     * Конвертирует PersistedAgentMessage в AgentMessage
     */
    fun PersistedAgentMessage.toAgentMessage(): AgentMessage {
        return AgentMessage(
            role = Role.valueOf(role),
            content = content,
            timestamp = timestamp
        )
    }
    
    /**
     * Конвертирует список AgentMessage в ChatSession
     * 
     * @param sessionId ID сессии (если null, генерируется новый UUID)
     * @param model название модели
     * @param createdAt время создания (если null, используется время первого сообщения или текущее)
     */
    fun List<AgentMessage>.toSession(
        sessionId: String? = null,
        model: String? = null,
        createdAt: Long? = null
    ): ChatSession {
        val now = System.currentTimeMillis()
        return ChatSession(
            id = sessionId ?: UUID.randomUUID().toString(),
            messages = map { it.toPersisted() },
            createdAt = createdAt ?: firstOrNull()?.timestamp ?: now,
            updatedAt = lastOrNull()?.timestamp ?: now,
            model = model
        )
    }
    
    /**
     * Конвертирует ChatSession в список AgentMessage
     */
    fun ChatSession.toMessages(): List<AgentMessage> {
        return messages.map { it.toAgentMessage() }
    }
    
    /**
     * Конвертирует список PersistedAgentMessage в список AgentMessage
     */
    fun List<PersistedAgentMessage>.toAgentMessages(): List<AgentMessage> {
        return map { it.toAgentMessage() }
    }
    
    /**
     * Конвертирует список AgentMessage в список PersistedAgentMessage
     */
    fun List<AgentMessage>.toPersistedMessages(): List<PersistedAgentMessage> {
        return map { it.toPersisted() }
    }
}
