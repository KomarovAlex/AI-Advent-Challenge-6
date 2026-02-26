package ru.koalexse.aichallenge.agent.context.strategy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.koalexse.aichallenge.agent.AgentMessage
import ru.koalexse.aichallenge.agent.Role
import ru.koalexse.aichallenge.agent.context.summary.ConversationSummary
import ru.koalexse.aichallenge.agent.context.summary.SummaryProvider
import ru.koalexse.aichallenge.agent.context.summary.SummaryStorage

/**
 * Стратегия обрезки контекста с компрессией через summary
 *
 * Принцип работы:
 * 1. Последние N сообщений (keepRecentCount) хранятся "как есть"
 * 2. Когда накапливается достаточно старых сообщений (summaryBlockSize),
 *    они сжимаются в summary через SummaryProvider
 * 3. Summary хранится в SummaryStorage и подставляется в начало истории
 *    как системное сообщение при запросе к LLM
 * 4. Оригинальные сообщения сохраняются в ConversationSummary для отображения
 *    в UI с пометкой "сжатые" — в LLM они не отправляются
 *
 * @param summaryProvider провайдер для генерации summary
 * @param summaryStorage хранилище для сохранения summaries
 * @param keepRecentCount количество последних сообщений, которые не сжимаются
 * @param summaryBlockSize минимальное количество сообщений для создания summary
 * @param tokenEstimator функция для оценки количества токенов в сообщении
 */
class SummaryTruncationStrategy(
    private val summaryProvider: SummaryProvider,
    private val summaryStorage: SummaryStorage,
    private val keepRecentCount: Int = DEFAULT_KEEP_RECENT_COUNT,
    private val summaryBlockSize: Int = DEFAULT_SUMMARY_BLOCK_SIZE,
    private val tokenEstimator: (AgentMessage) -> Int = { message ->
        (message.content.length / 4).coerceAtLeast(1)
    }
) : ContextTruncationStrategy {

    init {
        require(keepRecentCount > 0) { "keepRecentCount must be positive" }
        require(summaryBlockSize > 0) { "summaryBlockSize must be positive" }
    }

    override suspend fun truncate(
        messages: List<AgentMessage>,
        maxTokens: Int?,
        maxMessages: Int?
    ): List<AgentMessage> {
        if (messages.isEmpty()) return messages

        val recentMessages = messages.takeLast(keepRecentCount)
        val oldMessages = messages.dropLast(keepRecentCount)

        if (oldMessages.size >= summaryBlockSize) {
            val summaryText = summaryProvider.summarize(oldMessages)

            val summary = ConversationSummary(
                content = summaryText,
                // Сохраняем оригинальные сообщения для отображения в UI
                originalMessages = oldMessages
            )
            summaryStorage.addSummary(summary)

            return recentMessages
        }

        // Если summary не нужно — применяем обычные ограничения
        var result = messages

        if (maxMessages != null && result.size > maxMessages) {
            result = result.takeLast(maxMessages)
        }

        if (maxTokens != null) {
            result = truncateByTokens(result, maxTokens)
        }

        return result
    }

    /**
     * Возвращает все summaries как системные сообщения для подстановки в запрос к LLM
     */
    suspend fun getSummariesAsMessages(): List<AgentMessage> = withContext(Dispatchers.IO) {
        val summaries = summaryStorage.getSummaries()
        if (summaries.isEmpty()) return@withContext emptyList()

        val combinedSummary = buildString {
            append("Previous conversation summary:\n")
            summaries.forEachIndexed { index, summary ->
                if (summaries.size > 1) {
                    append("[Part ${index + 1}] ")
                }
                append(summary.content)
                if (index < summaries.lastIndex) append("\n\n")
            }
        }

        return@withContext listOf(AgentMessage(role = Role.SYSTEM, content = combinedSummary))
    }

    suspend fun clearSummaries() {
        summaryStorage.clear()
    }

    private fun truncateByTokens(messages: List<AgentMessage>, maxTokens: Int): List<AgentMessage> {
        if (messages.isEmpty()) return messages

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
            listOf(messages.last())
        }
    }

    companion object {
        const val DEFAULT_KEEP_RECENT_COUNT = 10
        const val DEFAULT_SUMMARY_BLOCK_SIZE = 10
    }
}
