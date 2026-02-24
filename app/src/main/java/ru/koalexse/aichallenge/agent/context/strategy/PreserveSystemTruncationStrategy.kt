package ru.koalexse.aichallenge.agent.context.strategy

import ru.koalexse.aichallenge.agent.AgentMessage
import ru.koalexse.aichallenge.agent.Role

/**
 * Стратегия обрезки, сохраняющая системные сообщения
 * 
 * При обрезке истории системные сообщения (role = SYSTEM) всегда сохраняются в начале.
 * Удаляются только сообщения USER и ASSISTANT, начиная с самых старых.
 * 
 * Это полезно для сохранения важных инструкций и настроек поведения агента.
 * 
 * @param tokenEstimator функция для оценки количества токенов в сообщении
 */
class PreserveSystemTruncationStrategy(
    private val tokenEstimator: (AgentMessage) -> Int = { message ->
        (message.content.length / 4).coerceAtLeast(1)
    }
) : ContextTruncationStrategy {
    
    override fun truncate(
        messages: List<AgentMessage>,
        maxTokens: Int?,
        maxMessages: Int?
    ): List<AgentMessage> {
        if (messages.isEmpty()) return messages
        
        // Разделяем системные и обычные сообщения
        val systemMessages = messages.filter { it.role == Role.SYSTEM }
        val nonSystemMessages = messages.filter { it.role != Role.SYSTEM }
        
        var truncatedNonSystem = nonSystemMessages
        
        // Ограничиваем по количеству сообщений (не считая системные)
        if (maxMessages != null) {
            val maxNonSystemMessages = (maxMessages - systemMessages.size).coerceAtLeast(0)
            if (truncatedNonSystem.size > maxNonSystemMessages) {
                truncatedNonSystem = truncatedNonSystem.takeLast(maxNonSystemMessages)
            }
        }
        
        // Ограничиваем по токенам
        if (maxTokens != null) {
            val systemTokens = systemMessages.sumOf { tokenEstimator(it) }
            val availableTokens = (maxTokens - systemTokens).coerceAtLeast(0)
            truncatedNonSystem = truncateByTokens(truncatedNonSystem, availableTokens)
        }
        
        // Собираем результат: системные сообщения + обрезанные обычные
        // Сохраняем порядок системных сообщений относительно их позиций
        return buildResultPreservingOrder(messages, systemMessages, truncatedNonSystem)
    }
    
    /**
     * Собирает результат, сохраняя относительный порядок сообщений
     */
    private fun buildResultPreservingOrder(
        original: List<AgentMessage>,
        systemMessages: List<AgentMessage>,
        truncatedNonSystem: List<AgentMessage>
    ): List<AgentMessage> {
        val systemSet = systemMessages.toSet()
        val nonSystemSet = truncatedNonSystem.toSet()
        
        return original.filter { msg ->
            msg in systemSet || msg in nonSystemSet
        }
    }
    
    /**
     * Обрезает список сообщений по количеству токенов
     */
    private fun truncateByTokens(
        messages: List<AgentMessage>,
        maxTokens: Int
    ): List<AgentMessage> {
        if (messages.isEmpty() || maxTokens <= 0) return emptyList()
        
        var totalTokens = 0
        var startIndex = messages.size
        
        for (i in messages.indices.reversed()) {
            val messageTokens = tokenEstimator(messages[i])
            if (totalTokens + messageTokens <= maxTokens) {
                totalTokens += messageTokens
                startIndex = i
            } else {
                break
            }
        }
        
        return if (startIndex < messages.size) {
            messages.subList(startIndex, messages.size)
        } else if (messages.isNotEmpty()) {
            // Хотя бы последнее сообщение, даже если превышает лимит
            listOf(messages.last())
        } else {
            emptyList()
        }
    }
}
