package ru.koalexse.aichallenge.agent.context.strategy

import ru.koalexse.aichallenge.agent.AgentMessage

/**
 * Стратегия управления размером контекста.
 * Определяет, как обрезать историю при достижении лимитов,
 * и может предоставлять дополнительные системные сообщения для LLM-запроса.
 */
interface ContextTruncationStrategy {

    /**
     * Обрезает список сообщений до нужного размера.
     *
     * @param messages исходные сообщения (порядок: старые → новые)
     * @param maxTokens максимальное количество токенов (null = без ограничения)
     * @param maxMessages максимальное количество сообщений (null = без ограничения)
     * @return обрезанный список сообщений
     */
    suspend fun truncate(
        messages: List<AgentMessage>,
        maxTokens: Int?,
        maxMessages: Int?
    ): List<AgentMessage>

    /**
     * Возвращает дополнительные системные сообщения, которые должны быть подставлены
     * в LLM-запрос перед активной историей.
     *
     * По умолчанию — пустой список. Стратегии с компрессией (например,
     * [SummaryTruncationStrategy]) переопределяют этот метод, чтобы вернуть
     * текст summary как системное сообщение.
     *
     * Это позволяет [ru.koalexse.aichallenge.agent.SimpleLLMAgent] работать
     * с любой стратегией через единый интерфейс, без приведения типов.
     */
    suspend fun getAdditionalSystemMessages(): List<AgentMessage> = emptyList()

    /**
     * Очищает внутреннее состояние стратегии (summaries, facts, branches и т.п.).
     *
     * Вызывается агентом при [ru.koalexse.aichallenge.agent.Agent.clearHistory].
     * По умолчанию — no-op. Стратегии с персистентным состоянием переопределяют.
     */
    suspend fun clear() {}
}
