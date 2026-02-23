package ru.koalexse.aichallenge.agent

import kotlinx.coroutines.flow.Flow

/**
 * Базовый интерфейс агента для работы с LLM
 * 
 * Агент инкапсулирует логику взаимодействия с языковой моделью,
 * включая формирование запросов, обработку ответов и управление контекстом.
 * 
 * Агент является чистой сущностью без зависимостей на Android фреймворк
 * и может использоваться в любом окружении (Android, CLI, тесты).
 */
interface Agent {
    
    /**
     * Текущая конфигурация агента
     */
    val config: AgentConfig
    
    /**
     * История диалога (если keepConversationHistory = true)
     */
    val conversationHistory: List<AgentMessage>
    
    /**
     * Отправляет запрос и получает полный ответ (не-стриминговый режим)
     * 
     * Этот метод блокирует до получения полного ответа.
     * Подходит для простых сценариев, когда стриминг не нужен.
     * 
     * @param request запрос к агенту
     * @return полный ответ агента
     * @throws AgentException в случае ошибки
     */
    suspend fun chat(request: AgentRequest): AgentResponse
    
    /**
     * Отправляет запрос и получает ответ в виде потока (стриминговый режим)
     * 
     * Позволяет получать частичные ответы по мере их генерации.
     * Подходит для UI, где важна отзывчивость.
     * 
     * @param request запрос к агенту
     * @return поток событий стриминга
     */
    fun chatStream(request: AgentRequest): Flow<AgentStreamEvent>
    
    /**
     * Упрощённый метод для быстрой отправки сообщения
     * Использует настройки по умолчанию из конфигурации
     * 
     * @param message текст сообщения пользователя
     * @return поток событий стриминга
     */
    fun send(message: String): Flow<AgentStreamEvent>
    
    /**
     * Очищает историю диалога
     */
    fun clearHistory()
    
    /**
     * Добавляет сообщение в историю вручную
     * Полезно для восстановления контекста или инъекции сообщений
     * 
     * @param message сообщение для добавления
     */
    fun addToHistory(message: AgentMessage)
    
    /**
     * Обновляет конфигурацию агента
     * 
     * @param newConfig новая конфигурация
     */
    fun updateConfig(newConfig: AgentConfig)
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
