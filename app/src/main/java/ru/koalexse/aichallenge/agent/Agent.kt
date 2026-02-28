package ru.koalexse.aichallenge.agent

import kotlinx.coroutines.flow.Flow
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
 * Контекст диалога инкапсулирован внутри агента — доступ к истории
 * только через [conversationHistory], мутации — только через методы агента.
 */
interface Agent {

    /**
     * Текущая конфигурация агента
     */
    val config: AgentConfig

    /**
     * Стратегия обрезки контекста (null = без обрезки)
     *
     * Стратегия применяется агентом после добавления сообщений в историю.
     * Позволяет ограничивать размер истории или сжимать старые сообщения.
     */
    val truncationStrategy: ContextTruncationStrategy?

    /**
     * Read-only снимок истории диалога.
     *
     * Только для чтения — мутации истории возможны исключительно
     * через [addToHistory] и [clearHistory].
     */
    val conversationHistory: List<AgentMessage>

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
     * Очищает историю диалога
     */
    suspend fun clearHistory()

    /**
     * Добавляет сообщение в историю вручную.
     * Полезно для восстановления контекста или инъекции сообщений.
     *
     * После добавления применяется стратегия обрезки, если она установлена.
     *
     * @param message сообщение для добавления
     */
    suspend fun addToHistory(message: AgentMessage)

    /**
     * Обновляет конфигурацию агента
     *
     * @param newConfig новая конфигурация
     */
    fun updateConfig(newConfig: AgentConfig)

    /**
     * Обновляет стратегию обрезки контекста
     *
     * @param strategy новая стратегия (null = отключить обрезку)
     */
    fun updateTruncationStrategy(strategy: ContextTruncationStrategy?)
}

/**
 * Исключения агента
 */
sealed class AgentException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /**
     * Ошибка API (сетевая ошибка, ошибка сервера и т.д.)
     */
    class ApiError(
        message: String,
        val statusCode: Int? = null,
        cause: Throwable? = null
    ) : AgentException(message, cause)

    /**
     * Ошибка конфигурации (неверные параметры)
     */
    class ConfigurationError(message: String) : AgentException(message)

    /**
     * Ошибка валидации запроса
     */
    class ValidationError(message: String) : AgentException(message)

    /**
     * Таймаут запроса
     */
    class TimeoutError(message: String, cause: Throwable? = null) : AgentException(message, cause)
}
