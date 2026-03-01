package ru.koalexse.aichallenge.agent.context.strategy

import ru.koalexse.aichallenge.agent.AgentMessage

/**
 * Стратегия «скользящее окно»: хранит только последние [windowSize] сообщений,
 * всё остальное отбрасывается без компрессии.
 *
 * Порядок применения:
 * 1. Ограничение по количеству сообщений ([windowSize] имеет приоритет над [maxMessages] из конфига).
 * 2. Ограничение по токенам через [TruncationUtils].
 *
 * @param windowSize максимальное количество хранимых сообщений (по умолчанию 10)
 * @param tokenEstimator функция оценки токенов. По умолчанию — [TokenEstimators.default].
 */
class SlidingWindowStrategy(
    val windowSize: Int = DEFAULT_WINDOW_SIZE,
    private val tokenEstimator: TokenEstimator = TokenEstimators.default
) : ContextTruncationStrategy {

    init {
        require(windowSize > 0) { "windowSize must be positive" }
    }

    override suspend fun truncate(
        messages: List<AgentMessage>,
        maxTokens: Int?,
        maxMessages: Int?
    ): List<AgentMessage> {
        if (messages.isEmpty()) return messages

        // windowSize имеет приоритет; но не превышаем maxMessages из конфига
        val limit = if (maxMessages != null) minOf(windowSize, maxMessages) else windowSize
        var result = if (messages.size > limit) messages.takeLast(limit) else messages

        if (maxTokens != null) {
            result = TruncationUtils.truncateByTokens(result, maxTokens, tokenEstimator)
        }

        return result
    }

    companion object {
        const val DEFAULT_WINDOW_SIZE = 10
    }
}
