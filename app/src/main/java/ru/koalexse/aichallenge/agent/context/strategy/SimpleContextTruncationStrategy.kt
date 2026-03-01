package ru.koalexse.aichallenge.agent.context.strategy

import ru.koalexse.aichallenge.agent.AgentMessage

/**
 * Простая стратегия обрезки контекста.
 *
 * Удаляет старейшие сообщения при превышении лимитов.
 * Сначала применяется ограничение по количеству сообщений, затем по токенам.
 *
 * @param tokenEstimator функция оценки токенов. По умолчанию — [TokenEstimators.default].
 */
class SimpleContextTruncationStrategy(
    private val tokenEstimator: TokenEstimator = TokenEstimators.default
) : ContextTruncationStrategy {

    override suspend fun truncate(
        messages: List<AgentMessage>,
        maxTokens: Int?,
        maxMessages: Int?
    ): List<AgentMessage> {
        if (messages.isEmpty()) return messages

        var result = messages

        if (maxMessages != null && result.size > maxMessages) {
            result = result.takeLast(maxMessages)
        }

        if (maxTokens != null) {
            result = TruncationUtils.truncateByTokens(result, maxTokens, tokenEstimator)
        }

        return result
    }
}
