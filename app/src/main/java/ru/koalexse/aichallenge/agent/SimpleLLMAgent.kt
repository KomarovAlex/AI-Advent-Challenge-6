package ru.koalexse.aichallenge.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import ru.koalexse.aichallenge.agent.context.AgentContext
import ru.koalexse.aichallenge.agent.context.SimpleAgentContext
import ru.koalexse.aichallenge.agent.context.strategy.ContextTruncationStrategy
import ru.koalexse.aichallenge.agent.context.strategy.SummaryTruncationStrategy
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
 * - Поддерживает компрессию истории через ContextTruncationStrategy
 * - Собирает статистику (время ответа, токены)
 * - Не зависит от Android фреймворка
 * 
 * @param api интерфейс для работы с LLM API
 * @param initialConfig начальная конфигурация агента
 * @param agentContext контекст для управления историей (по умолчанию создаётся SimpleAgentContext)
 * @param truncationStrategy стратегия обрезки контекста (null = без обрезки)
 */
class SimpleLLMAgent(
    private val api: StatsLLMApi,
    initialConfig: AgentConfig,
    agentContext: AgentContext? = null,
    truncationStrategy: ContextTruncationStrategy? = null
) : Agent {

    private var _config: AgentConfig = initialConfig
    private val _context: AgentContext = agentContext ?: SimpleAgentContext()
    private var _truncationStrategy: ContextTruncationStrategy? = truncationStrategy

    override val config: AgentConfig
        get() = synchronized(this) { _config }

    override val context: AgentContext
        get() = _context

    override val truncationStrategy: ContextTruncationStrategy?
        get() = synchronized(this) { _truncationStrategy }

    /**
     * Полный (не-стриминговый) режим общения с агентом
     * Собирает весь ответ и возвращает его целиком
     */
    override suspend fun chat(request: AgentRequest): AgentResponse {
        return withContext(Dispatchers.IO) {
            if (config.keepConversationHistory) {
                addMessageWithTruncation(
                    AgentMessage(
                        role = Role.USER,
                        content = request.userMessage
                    )
                )
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
                addMessageWithTruncation(
                    AgentMessage(
                        role = Role.ASSISTANT,
                        content = responseContent
                    )
                )
            }

            return@withContext AgentResponse(
                content = responseContent,
                tokenStats = finalStats,
                durationMs = finalDuration,
                model = request.model
            )
        }
    }

    /**
     * Стриминговый режим общения с агентом
     * Возвращает Flow с событиями по мере их поступления
     * 
     * Важно: сообщения добавляются в историю при получении Completed события,
     * чтобы подписчики могли сразу получить актуальную историю.
     */
    override suspend fun chatStream(request: AgentRequest): Flow<AgentStreamEvent> {
        if (config.keepConversationHistory) {
            addMessageWithTruncation(AgentMessage(role = Role.USER, content = request.userMessage))
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
                            addMessageWithTruncation(
                                AgentMessage(
                                    role = Role.ASSISTANT,
                                    content = responseBuilder.toString()
                                )
                            )
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
    override suspend fun send(message: String): Flow<AgentStreamEvent> {
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

    override suspend fun addToHistory(message: AgentMessage) {
        addMessageWithTruncation(message)
    }

    override fun updateConfig(newConfig: AgentConfig) {
        synchronized(this) {
            _config = newConfig
        }
    }

    override suspend fun clearHistory() {
        super.clearHistory()
        (truncationStrategy as? SummaryTruncationStrategy)?.clearSummaries()
    }

    override fun updateTruncationStrategy(strategy: ContextTruncationStrategy?) {
        synchronized(this) {
            _truncationStrategy = strategy
        }
    }

    // ==================== Вспомогательные методы ====================

    /**
     * Добавляет сообщение в контекст и применяет стратегию обрезки
     */
    private suspend fun addMessageWithTruncation(message: AgentMessage) {
        _context.addMessage(message)
        applyTruncation()
    }

    /**
     * Применяет стратегию обрезки к истории
     */
    private suspend fun applyTruncation(): Unit = withContext(Dispatchers.IO) {
        val strategy = _truncationStrategy ?: return@withContext
        val history = _context.getHistory()
        if (history.isEmpty()) return@withContext

        val truncated = strategy.truncate(
            messages = history,
            maxTokens = config.maxTokens,
            maxMessages = config.maxHistorySize
        )

        if (truncated.size != history.size) {
            _context.replaceHistory(truncated)
        }
    }

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
    private suspend fun buildChatRequest(request: AgentRequest): ChatRequest {
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
     * 
     * Если используется SummaryTruncationStrategy, подставляет summaries
     * в начало истории как системные сообщения.
     */
    private suspend fun buildMessageList(request: AgentRequest): List<ApiMessage> {
        val messages = mutableListOf<ApiMessage>()

        // Добавляем системный промпт, если есть
        val systemPrompt = request.systemPrompt ?: config.defaultSystemPrompt
        if (!systemPrompt.isNullOrBlank()) {
            messages.add(ApiMessage(role = "system", content = systemPrompt))
        }

        // Добавляем summaries, если стратегия поддерживает
        val summaryMessages = getSummaryMessages()
        summaryMessages.forEach { msg ->
            messages.add(msg.toApiMessage())
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
     * Получает summary сообщения из стратегии, если она поддерживает компрессию
     */
    private suspend fun getSummaryMessages(): List<AgentMessage> {
        val strategy = _truncationStrategy
        return if (strategy is SummaryTruncationStrategy) {
            strategy.getSummariesAsMessages()
        } else {
            emptyList()
        }
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
