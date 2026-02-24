package ru.koalexse.aichallenge.agent.context.strategy

import ru.koalexse.aichallenge.agent.AgentMessage

/**
 * Простая стратегия обрезки контекста
 * 
 * Удаляет старейшие сообщения при превышении лимитов.
 * Сначала применяется ограничение по количеству сообщений,
 * затем по токенам (если задано).
 * 
 * @param tokenEstimator функция для оценки количества токенов в сообщении
 *                       По умолчанию использует простую эвристику: ~4 символа = 1 токен
 */
class SimpleContextTruncationStrategy(
    private val tokenEstimator: (AgentMessage) -> Int = { message ->
        // Простая эвристика: примерно 4 символа = 1 токен для английского текста
        // Для русского текста это соотношение может быть ~2-3 символа = 1 токен
        (message.content.length / 4).coerceAtLeast(1)
    }
) : ContextTruncationStrategy {
    
    override fun truncate(
        messages: List<AgentMessage>,
        maxTokens: Int?,
        maxMessages: Int?
    ): List<AgentMessage> {
        if (messages.isEmpty()) return messages
        
        var result = messages
        
        // Сначала ограничиваем по количеству сообщений
        if (maxMessages != null && result.size > maxMessages) {
            result = result.takeLast(maxMessages)
        }
        
        // Затем ограничиваем по токенам (если задано)
        if (maxTokens != null) {
            result = truncateByTokens(result, maxTokens)
        }
        
        return result
    }
    
    /**
     * Обрезает список сообщений по количеству токенов
     * Удаляет старейшие сообщения, пока общее количество токенов не станет <= maxTokens
     */
    private fun truncateByTokens(
        messages: List<AgentMessage>,
        maxTokens: Int
    ): List<AgentMessage> {
        if (messages.isEmpty()) return messages
        
        // Считаем токены с конца (самые новые сообщения)
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
        } else {
            // Даже одно последнее сообщение превышает лимит — возвращаем его
            listOf(messages.last())
        }
    }
}
