package ru.koalexse.aichallenge.agent

import kotlinx.coroutines.flow.Flow
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
 * Контекст диалога и summaries инкапсулированы внутри агента:
 * - история — только через [conversationHistory] (read-only)
 * - summaries — только через [getSummaries] / [loadSummaries]
 * - мутации — только через методы агента
 */
interface Agent {

    /**
     * Текущая конфигурация агента
     */
    val config: AgentConfig

    /**
     * Стратегия обрезки контекста (null = без обрезки)
     */
    val truncationStrategy: ContextTruncationStrategy?

    /**
     * Read-only снимок активной истории диалога.
     * Не включает сообщения, сжатые в summaries.
     */
    val conversationHistory: List<AgentMessage>

    /**
     * Возвращает список сжатых summaries.
     * Используется ViewModel для отображения сжатых сообщений в UI
     * и для сохранения сессии.
     *
     * Если стратегия компрессии не установлена — возвращает пустой список.
     */
    suspend fun getSummaries(): List<ConversationSummary>

    /**
     * Загружает summaries при восстановлении сессии.
     * Если стратегия компрессии не установлена — игнорирует вызов.
     *
     * @param summaries список summaries для загрузки
     */
    suspend fun loadSummaries(summaries: List<ConversationSummary>)

    /**
     * Отправляет запрос и получает полный ответ (не-стриминговый режим)
     *
     * @param request запрос к агенту
     * @return полный ответ агента
     * @throws AgentException в случае ошибки
     */
    suspend fun chat(request: AgentRequest): AgentResponse

    /**
     * Отправляет запрос и получает ответ в виде потока (стриминговый режим)
     *
     * @param request запрос к агенту
     * @return поток событий стриминга
     */
    suspend fun chatStream(request: AgentRequest): Flow<AgentStreamEvent>

    /**
     * Упрощённый метод для быстрой отправки сообщения.
     * Использует настройки по умолчанию из конфигурации.
     *
     * @param message текст сообщения пользователя
     * @return поток событий стриминга
     */
    suspend fun send(message: String): Flow<AgentStreamEvent>

    /**
     * Очищает историю диалога и все summaries
     */
    suspend fun clearHistory()

    /**
     * Добавляет сообщение в историю вручную.
     * После добавления применяется стратегия обрезки, если она установлена.
     *
     * @param message сообщение для добавления
     */
    suspend fun addToHistory(message: AgentMessage)

    /**
     * Обновляет конфигурацию агента
     */
    fun updateConfig(newConfig: AgentConfig)

    /**
     * Обновляет стратегию обрезки контекста
     */
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
