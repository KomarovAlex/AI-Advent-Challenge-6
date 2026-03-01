package ru.koalexse.aichallenge.agent

import kotlinx.coroutines.flow.Flow
import ru.koalexse.aichallenge.agent.context.branch.DialogBranch
import ru.koalexse.aichallenge.agent.context.strategy.BranchingStrategy
import ru.koalexse.aichallenge.agent.context.strategy.ContextTruncationStrategy

/**
 * Базовый интерфейс агента для работы с LLM.
 *
 * Агент инкапсулирует логику взаимодействия с языковой моделью,
 * включая формирование запросов, обработку ответов и управление контекстом.
 *
 * Агент является чистой сущностью без зависимостей на Android фреймворк
 * и может использоваться в любом окружении (Android, CLI, тесты).
 *
 * ### Доступ к возможностям стратегий
 * Стратегия-специфичные операции не входят в этот интерфейс (ISP).
 * Используйте приведение типа [truncationStrategy]:
 *
 * ```kotlin
 * // Summaries (SummaryTruncationStrategy):
 * (agent.truncationStrategy as? SummaryTruncationStrategy)?.getSummaries()
 * (agent.truncationStrategy as? SummaryTruncationStrategy)?.loadSummaries(list)
 *
 * // Facts (StickyFactsStrategy):
 * (agent.truncationStrategy as? StickyFactsStrategy)?.getFacts()
 * (agent.truncationStrategy as? StickyFactsStrategy)?.refreshFacts(history)
 * (agent.truncationStrategy as? StickyFactsStrategy)?.loadFacts(list)
 *
 * // Branches (BranchingStrategy):
 * (agent.truncationStrategy as? BranchingStrategy)?.getBranches()
 * ```
 *
 * Ветки ([initBranches], [createCheckpoint], [switchToBranch]) вынесены в интерфейс,
 * потому что агент обязан синхронизировать [conversationHistory] с активной веткой.
 */
interface Agent {

    /** Текущая конфигурация агента */
    val config: AgentConfig

    /** Стратегия обрезки контекста (null = без обрезки) */
    val truncationStrategy: ContextTruncationStrategy?

    /**
     * Read-only снимок активной истории диалога.
     * Не включает сообщения, сжатые в summaries.
     */
    val conversationHistory: List<AgentMessage>

    // ==================== Branches ====================
    // Операции с ветками остаются в интерфейсе Agent, т.к. требуют синхронизации
    // _context (conversationHistory) с активной веткой — это зона ответственности агента.
    // Summaries и Facts — чистые I/O операции со стратегией, синхронизации не требуют.

    /**
     * Инициализирует ветки при старте агента с [BranchingStrategy].
     * Если ветки не создавались — создаёт первую «Branch 1».
     * Если активна не [BranchingStrategy] — no-op.
     */
    suspend fun initBranches()

    /**
     * Создаёт checkpoint: сохраняет текущую ветку и создаёт новую ветку-копию.
     * Если активна не [BranchingStrategy] или достигнут лимит — возвращает null.
     *
     * @return новая ветка, или null если не поддерживается / лимит достигнут
     */
    suspend fun createCheckpoint(): DialogBranch?

    /**
     * Переключается на ветку с [branchId].
     * После переключения [conversationHistory] заменяется на историю выбранной ветки.
     * Если активна не [BranchingStrategy] — no-op.
     *
     * @return true если переключение выполнено успешно
     */
    suspend fun switchToBranch(branchId: String): Boolean

    /**
     * Возвращает id активной ветки.
     * Если активна не [BranchingStrategy] — возвращает null.
     */
    suspend fun getActiveBranchId(): String?

    /**
     * Возвращает все ветки диалога.
     * Если активна не [BranchingStrategy] — возвращает пустой список.
     */
    suspend fun getBranches(): List<DialogBranch>

    // ==================== Core ====================

    /**
     * Отправляет запрос и получает полный ответ (не-стриминговый режим)
     */
    suspend fun chat(request: AgentRequest): AgentResponse

    /**
     * Отправляет запрос и получает ответ в виде потока (стриминговый режим)
     */
    suspend fun chatStream(request: AgentRequest): Flow<AgentStreamEvent>

    /**
     * Упрощённый метод для быстрой отправки сообщения.
     * Использует настройки по умолчанию из конфигурации.
     */
    suspend fun send(message: String): Flow<AgentStreamEvent>

    /**
     * Очищает историю диалога и делегирует очистку состояния активной стратегии
     * через [ContextTruncationStrategy.clear].
     */
    suspend fun clearHistory()

    /**
     * Добавляет сообщение в историю вручную.
     * После добавления применяется стратегия обрезки, если она установлена.
     */
    suspend fun addToHistory(message: AgentMessage)

    /** Обновляет конфигурацию агента */
    fun updateConfig(newConfig: AgentConfig)

    /** Обновляет стратегию обрезки контекста */
    fun updateTruncationStrategy(strategy: ContextTruncationStrategy?)
}

/**
 * Исключения агента
 */
sealed class AgentException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    class ApiError(
        message: String,
        val statusCode: Int? = null,
        cause: Throwable? = null
    ) : AgentException(message, cause)

    class ConfigurationError(message: String) : AgentException(message)

    class ValidationError(message: String) : AgentException(message)

    class TimeoutError(message: String, cause: Throwable? = null) : AgentException(message, cause)
}
