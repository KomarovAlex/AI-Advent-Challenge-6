package ru.koalexse.aichallenge.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import ru.koalexse.aichallenge.agent.context.AgentContext
import ru.koalexse.aichallenge.agent.context.SimpleAgentContext
import ru.koalexse.aichallenge.data.StatsLLMApi
import ru.koalexse.aichallenge.domain.ApiMessage
import ru.koalexse.aichallenge.domain.ChatRequest
import ru.koalexse.aichallenge.domain.StatsStreamResult
import ru.koalexse.aichallenge.domain.TokenStats
import java.net.SocketTimeoutException

/**
 * Простая реализация агента для работы с LLM через StatsLLMApi
 * 
 * Особенности:
 * - Поддерживает как стриминговый, так и полный режим ответа
 * - Автоматически управляет историей диалога через AgentContext
 * - Собирает статистику (время ответа, токены)
 * - Не зависит от Android фреймворка
 * 
 * @param api интерфейс для работы с LLM API
 * @param initialConfig начальная конфигурация агента
 * @param agentContext контекст для управления историей (по умолчанию создаётся SimpleAgentContext)
 */
class SimpleLLMAgent(
    private val api: StatsLLMApi,
    initialConfig: AgentConfig,
    agentContext: AgentContext? = null
) : Agent {

    private var _config: AgentConfig = initialConfig
    private val _context: AgentContext = agentContext ?: SimpleAgentContext(
        maxHistorySize = initialConfig.maxHistorySize
    )

    override val config: AgentConfig
        get() = synchronized(this) { _config }

    override val context: AgentContext
        get() = _context

    /**
     * Полный (не-стриминговый) режим общения с агентом
     * Собирает весь ответ и возвращает его целиком
     */
    override suspend fun chat(request: AgentRequest): AgentResponse {
        if (config.keepConversationHistory) {
            _context.addUserMessage(request.userMessage)
        }
        validateRequest(request)
        val chatRequest = buildChatRequest(request)
        val responseBuilder = StringBuilder()
        var finalStats: TokenStats? = null
        var finalDuration: Long? = null

        // Собираем все чанки в один ответ
        api.sendMessageStream(chatRequest).collect { result ->
            when (result) {
                is StatsStreamResult.Content -> {
                    responseBuilder.append(result.text)
                }

                is StatsStreamResult.Stats -> {
                    finalStats = result.tokenStats
                    finalDuration = result.durationMs
                }
            }
        }

        val responseContent = responseBuilder.toString()

        // Сохраняем в историю, если включено
        if (config.keepConversationHistory) {
            _context.addAssistantMessage(responseContent)
        }

        return AgentResponse(
            content = responseContent,
            tokenStats = finalStats,
            durationMs = finalDuration,
            model = request.model
        )
    }

    /**
     * Стриминговый режим общения с агентом
     * Возвращает Flow с событиями по мере их поступления
     * 
     * Важно: сообщения добавляются в историю при получении Completed события,
     * чтобы подписчики могли сразу получить актуальную историю.
     */
    override fun chatStream(request: AgentRequest): Flow<AgentStreamEvent> {
        if (config.keepConversationHistory) {
            _context.addUserMessage(request.userMessage)
        }
        validateRequest(request)

        val chatRequest = buildChatRequest(request)
        val responseBuilder = StringBuilder()

        return api.sendMessageStream(chatRequest)
            .map<StatsStreamResult, AgentStreamEvent> { result ->
                when (result) {
                    is StatsStreamResult.Content -> {
                        responseBuilder.append(result.text)
                        AgentStreamEvent.ContentDelta(result.text)
                    }

                    is StatsStreamResult.Stats -> {
                        // Сохраняем в историю ДО эмиссии Completed
                        // чтобы подписчики получили актуальную историю
                        if (config.keepConversationHistory && responseBuilder.isNotEmpty()) {
                            _context.addAssistantMessage(responseBuilder.toString())
                        }

                        AgentStreamEvent.Completed(
                            tokenStats = result.tokenStats,
                            durationMs = result.durationMs
                        )
                    }
                }
            }
            .catch { e ->
                emit(AgentStreamEvent.Error(wrapException(e)))
            }
    }

    /**
     * Упрощённый метод для быстрой отправки сообщения
     * Использует настройки по умолчанию из конфигурации
     */
    override fun send(message: String): Flow<AgentStreamEvent> {
        val request = AgentRequest(
            userMessage = message,
            conversationHistory = if (config.keepConversationHistory) _context.getHistory() else emptyList(),
            systemPrompt = config.defaultSystemPrompt,
            model = config.defaultModel,
            temperature = config.defaultTemperature,
            maxTokens = config.defaultMaxTokens,
            stopSequences = config.defaultStopSequences
        )
        return chatStream(request)
    }

    override fun updateConfig(newConfig: AgentConfig) {
        synchronized(this) {
            _config = newConfig
            // Обновляем maxHistorySize в контексте, если он изменился
            if (newConfig.maxHistorySize != _context.maxHistorySize) {
                _context.updateMaxHistorySize(newConfig.maxHistorySize)
            }
        }
    }

    // ==================== Вспомогательные методы ====================

    /**
     * Валидация запроса
     */
    private fun validateRequest(request: AgentRequest) {
        if (request.userMessage.isBlank()) {
            throw AgentException.ValidationError("User message cannot be blank")
        }
        if (request.model.isBlank()) {
            throw AgentException.ValidationError("Model name cannot be blank")
        }
        request.temperature?.let {
            if (it < 0f || it > 2f) {
                throw AgentException.ValidationError("Temperature must be between 0.0 and 2.0")
            }
        }
        request.maxTokens?.let {
            if (it <= 0) {
                throw AgentException.ValidationError("Max tokens must be positive")
            }
        }
    }

    /**
     * Формирует ChatRequest из AgentRequest
     */
    private fun buildChatRequest(request: AgentRequest): ChatRequest {
        val messages = buildMessageList(request)

        return ChatRequest(
            messages = messages,
            model = request.model,
            temperature = request.temperature,
            max_tokens = request.maxTokens,
            stop = request.stopSequences ?: config.defaultStopSequences
        )
    }

    /**
     * Формирует список сообщений для API
     */
    private fun buildMessageList(request: AgentRequest): List<ApiMessage> {
        val messages = mutableListOf<ApiMessage>()

        // Добавляем системный промпт, если есть
        val systemPrompt = request.systemPrompt ?: config.defaultSystemPrompt
        if (!systemPrompt.isNullOrBlank()) {
            messages.add(ApiMessage(role = "system", content = systemPrompt))
        }

        // Добавляем историю из запроса
        request.conversationHistory.forEach { msg ->
            messages.add(msg.toApiMessage())
        }

        // Если история в запросе пуста, но включено сохранение истории, используем контекст
        if (request.conversationHistory.isEmpty() && config.keepConversationHistory) {
            _context.getHistory().forEach { msg ->
                messages.add(msg.toApiMessage())
            }
        }

        // Добавляем текущее сообщение пользователя
        messages.add(ApiMessage(role = "user", content = request.userMessage))

        return messages
    }

    /**
     * Оборачивает исключения в AgentException
     */
    private fun wrapException(e: Throwable): AgentException {
        return when (e) {
            is AgentException -> e
            is SocketTimeoutException -> AgentException.TimeoutError(
                "Request timed out: ${e.message}",
                e
            )

            else -> AgentException.ApiError(
                message = e.message ?: "Unknown error",
                cause = e
            )
        }
    }

    /**
     * Конвертирует AgentMessage в ApiMessage
     */
    private fun AgentMessage.toApiMessage(): ApiMessage {
        val roleString = when (role) {
            Role.USER -> "user"
            Role.ASSISTANT -> "assistant"
            Role.SYSTEM -> "system"
        }
        return ApiMessage(role = roleString, content = content)
    }
}
