package ru.koalexse.aichallenge.agent.context.strategy

import kotlinx.coroutines.runBlocking
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
 *    как системное сообщение
 * 
 * Пример:
 * - keepRecentCount = 5
 * - summaryBlockSize = 10
 * - История: [M1, M2, ..., M15]
 * - Результат: [Summary(M1..M10)] + [M11, M12, M13, M14, M15]
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
        
        // Разделяем на "старые" (для сжатия) и "новые" (сохраняем как есть)
        val recentMessages = messages.takeLast(keepRecentCount)
        val oldMessages = messages.dropLast(keepRecentCount)
        
        // Проверяем, нужно ли создавать summary
        if (oldMessages.size >= summaryBlockSize) {
            // Создаём summary из старых сообщений
            val summaryText = summaryProvider.summarize(oldMessages)
            
            val summary = ConversationSummary(
                content = summaryText,
                originalMessageCount = oldMessages.size
            )
            summaryStorage.addSummary(summary)
            
            // Возвращаем только недавние сообщения
            // Summary будет добавлено при формировании запроса к API
            return recentMessages
        }
        
        // Если summary не нужно, применяем обычные ограничения
        var result = messages
        
        // Ограничение по количеству сообщений
        if (maxMessages != null && result.size > maxMessages) {
            result = result.takeLast(maxMessages)
        }
        
        // Ограничение по токенам
        if (maxTokens != null) {
            result = truncateByTokens(result, maxTokens)
        }
        
        return result
    }
    
    /**
     * Возвращает все summaries как системные сообщения для добавления в контекст
     * 
     * Вызывается при формировании запроса к API, чтобы подставить
     * сохранённые summaries в начало истории.
     */
    suspend fun getSummariesAsMessagesSuspend(): List<AgentMessage> {
        val summaries = summaryStorage.getSummaries()
        if (summaries.isEmpty()) return emptyList()
        
        // Объединяем все summaries в одно сообщение
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
        
        return listOf(
            AgentMessage(
                role = Role.SYSTEM,
                content = combinedSummary
            )
        )
    }
    
    /**
     * Возвращает все summaries как системные сообщения для добавления в контекст
     * 
     * Синхронная версия для совместимости. Использует runBlocking.
     * Предпочитайте getSummariesAsMessagesSuspend() когда возможно.
     */
    fun getSummariesAsMessages(): List<AgentMessage> {
        return runBlocking {
            getSummariesAsMessagesSuspend()
        }
    }
    
    /**
     * Проверяет, есть ли сохранённые summaries
     */
    suspend fun hasSummaries(): Boolean = !summaryStorage.isEmpty()
    
    /**
     * Очищает все сохранённые summaries
     */
    suspend fun clearSummariesSuspend() {
        summaryStorage.clear()
    }
    
    /**
     * Очищает все сохранённые summaries
     * 
     * Синхронная версия для совместимости.
     */
    fun clearSummaries() {
        runBlocking {
            summaryStorage.clear()
        }
    }
    
    /**
     * Возвращает количество сообщений, сжатых в summaries
     */
    suspend fun getCompressedMessageCountSuspend(): Int {
        return summaryStorage.getSummaries().sumOf { it.originalMessageCount }
    }
    
    /**
     * Возвращает количество сообщений, сжатых в summaries
     * 
     * Синхронная версия для совместимости.
     */
    fun getCompressedMessageCount(): Int {
        return runBlocking {
            getCompressedMessageCountSuspend()
        }
    }
    
    /**
     * Обрезает список сообщений по количеству токенов
     */
    private fun truncateByTokens(
        messages: List<AgentMessage>,
        maxTokens: Int
    ): List<AgentMessage> {
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
