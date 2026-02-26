package ru.koalexse.aichallenge.agent.context.summary

import ru.koalexse.aichallenge.agent.AgentMessage

/**
 * Провайдер для генерации summary из списка сообщений
 * 
 * Отвечает за сжатие блока сообщений в краткое описание,
 * которое сохраняет ключевую информацию диалога.
 */
interface SummaryProvider {
    
    /**
     * Генерирует summary из списка сообщений
     * 
     * @param messages сообщения для суммаризации
     * @return текст summary
     */
    suspend fun summarize(messages: List<AgentMessage>): String
}
