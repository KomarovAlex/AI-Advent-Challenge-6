package ru.koalexse.aichallenge.agent.context.strategy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.koalexse.aichallenge.agent.AgentMessage
import ru.koalexse.aichallenge.agent.Role
import ru.koalexse.aichallenge.agent.context.summary.ConversationSummary
import ru.koalexse.aichallenge.agent.context.summary.SummaryProvider
import ru.koalexse.aichallenge.agent.context.summary.SummaryStorage

/**
 * Стратегия обрезки контекста с компрессией через summary.
 *
 * Принцип работы:
 * 1. Последние N сообщений (keepRecentCount) хранятся "как есть"
 * 2. Когда накапливается достаточно старых сообщений (summaryBlockSize),
 *    они сжимаются в summary через SummaryProvider
 * 3. Summary подставляется в LLM-запрос через [getAdditionalSystemMessages]
 * 4. Оригинальные сообщения хранятся для UI с пометкой "сжатые"
 *
 * @param summaryProvider провайдер для генерации summary
 * @param summaryStorage хранилище для сохранения summaries
 * @param keepRecentCount количество последних сообщений, которые не сжимаются
 * @param summaryBlockSize минимальное количество сообщений для создания summary
 * @param tokenEstimator функция оценки токенов. По умолчанию — [TokenEstimators.default].
 */
class SummaryTruncationStrategy(
    private val summaryProvider: SummaryProvider,
    private val summaryStorage: SummaryStorage,
    private val keepRecentCount: Int = DEFAULT_KEEP_RECENT_COUNT,
    private val summaryBlockSize: Int = DEFAULT_SUMMARY_BLOCK_SIZE,
    private val tokenEstimator: TokenEstimator = TokenEstimators.default
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
            summaryStorage.addSummary(
                ConversationSummary(
                    content = summaryText,
                    originalMessages = oldMessages
                )
            )
            return recentMessages
        }

        var result = messages

        if (maxMessages != null && result.size > maxMessages) {
            result = result.takeLast(maxMessages)
        }

        if (maxTokens != null) {
            result = TruncationUtils.truncateByTokens(result, maxTokens, tokenEstimator)
        }

        return result
    }

    /**
     * Возвращает накопленные summaries как системное сообщение для LLM-запроса.
     * Переопределяет метод интерфейса — агент вызывает его без приведения типов.
     */
    override suspend fun getAdditionalSystemMessages(): List<AgentMessage> =
        withContext(Dispatchers.IO) {
            val summaries = summaryStorage.getSummaries()
            if (summaries.isEmpty()) return@withContext emptyList()

            val combinedSummary = buildString {
                append("Previous conversation summary:\n")
                summaries.forEachIndexed { index, summary ->
                    if (summaries.size > 1) append("[Part ${index + 1}] ")
                    append(summary.content)
                    if (index < summaries.lastIndex) append("\n\n")
                }
            }

            listOf(AgentMessage(role = Role.SYSTEM, content = combinedSummary))
        }

    /**
     * Возвращает список [ConversationSummary] для UI и persistence.
     */
    suspend fun getSummaries(): List<ConversationSummary> =
        summaryStorage.getSummaries()

    /**
     * Загружает summaries при восстановлении сессии.
     */
    suspend fun loadSummaries(summaries: List<ConversationSummary>) =
        summaryStorage.loadSummaries(summaries)

    /**
     * Очищает все summaries.
     */
    suspend fun clearSummaries() =
        summaryStorage.clear()

    companion object {
        const val DEFAULT_KEEP_RECENT_COUNT = 10
        const val DEFAULT_SUMMARY_BLOCK_SIZE = 10
    }
}
