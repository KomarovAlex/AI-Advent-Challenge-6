package ru.koalexse.aichallenge.agent.context.strategy

import ru.koalexse.aichallenge.agent.AgentMessage

/**
 * Стратегия управления размером контекста
 * Определяет, как обрезать историю при достижении лимитов
 */
interface ContextTruncationStrategy {
    
    /**
     * Обрезает список сообщений до нужного размера
     * 
     * @param messages исходные сообщения
     * @param maxTokens максимальное количество токенов (null = без ограничения)
     * @param maxMessages максимальное количество сообщений (null = без ограничения)
     * @return обрезанный список сообщений
     */
    fun truncate(
        messages: List<AgentMessage>,
        maxTokens: Int?,
        maxMessages: Int?
    ): List<AgentMessage>
}
