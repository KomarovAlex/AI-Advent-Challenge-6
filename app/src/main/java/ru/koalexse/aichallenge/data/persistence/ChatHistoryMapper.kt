package ru.koalexse.aichallenge.data.persistence

import ru.koalexse.aichallenge.agent.AgentMessage
import ru.koalexse.aichallenge.agent.Role
import ru.koalexse.aichallenge.agent.context.summary.ConversationSummary
import ru.koalexse.aichallenge.domain.SessionTokenStats
import java.util.UUID

/**
 * Маппер для конвертации между доменными моделями и моделями для сериализации
 */
object ChatHistoryMapper {
    
    fun AgentMessage.toPersisted(): PersistedAgentMessage {
        return PersistedAgentMessage(
            role = role.name,
            content = content,
            timestamp = timestamp
        )
    }
    
    fun PersistedAgentMessage.toAgentMessage(): AgentMessage {
        return AgentMessage(
            role = Role.valueOf(role),
            content = content,
            timestamp = timestamp
        )
    }
    
    fun SessionTokenStats.toPersisted(): PersistedSessionStats {
        return PersistedSessionStats(
            totalPromptTokens = totalPromptTokens,
            totalCompletionTokens = totalCompletionTokens,
            totalTokens = totalTokens,
            messageCount = messageCount
        )
    }
    
    fun PersistedSessionStats.toSessionTokenStats(): SessionTokenStats {
        return SessionTokenStats(
            totalPromptTokens = totalPromptTokens,
            totalCompletionTokens = totalCompletionTokens,
            totalTokens = totalTokens,
            messageCount = messageCount
        )
    }
    
    fun ConversationSummary.toPersisted(): PersistedSummary {
        return PersistedSummary(
            content = content,
            originalMessages = originalMessages.map { it.toPersisted() },
            createdAt = createdAt
        )
    }
    
    fun PersistedSummary.toConversationSummary(): ConversationSummary {
        return ConversationSummary(
            content = content,
            originalMessages = originalMessages.map { it.toAgentMessage() },
            createdAt = createdAt
        )
    }
    
    fun List<AgentMessage>.toSession(
        sessionId: String? = null,
        model: String? = null,
        createdAt: Long? = null,
        sessionStats: SessionTokenStats? = null,
        summaries: List<ConversationSummary> = emptyList()
    ): ChatSession {
        val now = System.currentTimeMillis()
        return ChatSession(
            id = sessionId ?: UUID.randomUUID().toString(),
            messages = map { it.toPersisted() },
            createdAt = createdAt ?: firstOrNull()?.timestamp ?: now,
            updatedAt = lastOrNull()?.timestamp ?: now,
            model = model,
            sessionStats = sessionStats?.toPersisted(),
            summaries = summaries.map { it.toPersisted() }
        )
    }
    
    fun ChatSession.toMessages(): List<AgentMessage> {
        return messages.map { it.toAgentMessage() }
    }
    
    fun ChatSession.toSessionTokenStats(): SessionTokenStats? {
        return sessionStats?.toSessionTokenStats()
    }
    
    fun ChatSession.toSummaries(): List<ConversationSummary> {
        return summaries.map { it.toConversationSummary() }
    }
    
    fun List<PersistedAgentMessage>.toAgentMessages(): List<AgentMessage> {
        return map { it.toAgentMessage() }
    }
    
    fun List<AgentMessage>.toPersistedMessages(): List<PersistedAgentMessage> {
        return map { it.toPersisted() }
    }
    
    fun List<ConversationSummary>.toPersistedSummaries(): List<PersistedSummary> {
        return map { it.toPersisted() }
    }
    
    fun List<PersistedSummary>.toConversationSummaries(): List<ConversationSummary> {
        return map { it.toConversationSummary() }
    }
}
