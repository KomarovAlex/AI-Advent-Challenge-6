package ru.koalexse.aichallenge.agent

import kotlinx.coroutines.flow.Flow
import ru.koalexse.aichallenge.agent.context.branch.DialogBranch
import ru.koalexse.aichallenge.agent.context.facts.Fact
import ru.koalexse.aichallenge.agent.context.summary.ConversationSummary
import ru.koalexse.aichallenge.agent.context.strategy.ContextTruncationStrategy

/**
 * Базовый интерфейс агента для работы с LLM
 *
 * Агент инкапсулирует логику взаимодействия с языковой моделью,
 * включая формирование запросов, обработку ответов и управление контекстом.
 *
 * Агент является чистой сущностью без зависимостей на Android фреймворк
 * и может использоваться в любом окружении (Android, CLI, тесты).
 *
 * Контекст диалога, summaries, facts и branches инкапсулированы внутри агента:
 * - история — только через [conversationHistory] (read-only)
 * - summaries — только через [getSummaries] / [loadSummaries]
 * - facts — только через [getFacts] / [refreshFacts] / [loadFacts]
 * - branches — только через [getBranches] / [createCheckpoint] / [switchToBranch]
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

    // ==================== Summaries ====================

    /**
     * Возвращает список сжатых summaries.
     * Если стратегия компрессии не установлена — возвращает пустой список.
     */
    suspend fun getSummaries(): List<ConversationSummary>

    /**
     * Загружает summaries при восстановлении сессии.
     * Если стратегия компрессии не установлена — игнорирует вызов.
     */
    suspend fun loadSummaries(summaries: List<ConversationSummary>)

    // ==================== Facts (StickyFactsStrategy) ====================

    /**
     * Возвращает текущие факты.
     * Если активна не [StickyFactsStrategy] — возвращает пустой список.
     */
    suspend fun getFacts(): List<Fact>

    /**
     * Запускает LLM-вызов для обновления фактов на основе текущей истории.
     * Если активна не [StickyFactsStrategy] — no-op.
     *
     * @return обновлённый список фактов
     */
    suspend fun refreshFacts(): List<Fact>

    /**
     * Загружает факты при восстановлении сессии.
     * Если активна не [StickyFactsStrategy] — игнорирует вызов.
     */
    suspend fun loadFacts(facts: List<Fact>)

    // ==================== Branches (BranchingStrategy) ====================

    /**
     * Возвращает все ветки диалога.
     * Если активна не [BranchingStrategy] — возвращает пустой список.
     */
    suspend fun getBranches(): List<DialogBranch>

    /**
     * Возвращает id активной ветки.
     * Если активна не [BranchingStrategy] — возвращает null.
     */
    suspend fun getActiveBranchId(): String?

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
     * Инициализирует ветки при старте агента с BranchingStrategy.
     * Если ветки не создавались — создаёт первую «Branch 1».
     * Если активна не [BranchingStrategy] — no-op.
     */
    suspend fun initBranches()

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

    /** Очищает историю диалога, summaries, facts и branches */
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
