package ru.koalexse.aichallenge.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import ru.koalexse.aichallenge.agent.context.AgentContext
import ru.koalexse.aichallenge.agent.context.SimpleAgentContext
import ru.koalexse.aichallenge.agent.context.branch.DialogBranch
import ru.koalexse.aichallenge.agent.context.facts.Fact
import ru.koalexse.aichallenge.agent.context.strategy.BranchingStrategy
import ru.koalexse.aichallenge.agent.context.strategy.ContextTruncationStrategy
import ru.koalexse.aichallenge.agent.context.strategy.StickyFactsStrategy
import ru.koalexse.aichallenge.agent.context.strategy.SummaryTruncationStrategy
import ru.koalexse.aichallenge.agent.context.summary.ConversationSummary
import ru.koalexse.aichallenge.domain.ApiMessage
import ru.koalexse.aichallenge.domain.ChatRequest
import ru.koalexse.aichallenge.domain.StatsStreamResult
import ru.koalexse.aichallenge.domain.TokenStats
import java.net.SocketTimeoutException

/**
 * Простая реализация агента для работы с LLM через [StatsLLMApi].
 *
 * Особенности:
 * - Поддерживает стриминговый и полный режим ответа
 * - Инкапсулирует историю, summaries, facts и branches — снаружи только read-only доступ
 * - Поддерживает три стратегии управления контекстом:
 *   - [SlidingWindowStrategy] — скользящее окно
 *   - [StickyFactsStrategy] — ключевые факты из диалога
 *   - [BranchingStrategy] — ветки диалога
 * - Не зависит от Android фреймворка
 *
 * ### Потокобезопасность
 * - [_config] и [_truncationStrategy] помечены `@Volatile` — гарантирует видимость
 *   изменений между потоками без блокировки при чтении в suspend-функциях.
 * - Запись защищена `synchronized` только в не-suspend методах [updateConfig] и
 *   [updateTruncationStrategy], где приостановки корутины невозможны.
 * - [_context] ([SimpleAgentContext]) использует `synchronized` внутри.
 *
 * @param api интерфейс для работы с LLM API
 * @param initialConfig начальная конфигурация агента
 * @param agentContext контекст для управления историей (по умолчанию [SimpleAgentContext])
 * @param truncationStrategy стратегия обрезки контекста (null = без обрезки)
 */
class SimpleLLMAgent(
    private val api: StatsLLMApi,
    initialConfig: AgentConfig,
    agentContext: AgentContext? = null,
    truncationStrategy: ContextTruncationStrategy? = null
) : Agent {

    @Volatile
    private var _config: AgentConfig = initialConfig

    private val _context: AgentContext = agentContext ?: SimpleAgentContext()

    @Volatile
    private var _truncationStrategy: ContextTruncationStrategy? = truncationStrategy

    override val config: AgentConfig
        get() = _config

    override val truncationStrategy: ContextTruncationStrategy?
        get() = _truncationStrategy

    override val conversationHistory: List<AgentMessage>
        get() = _context.getHistory()

    // ==================== Summaries ====================

    override suspend fun getSummaries(): List<ConversationSummary> {
        val strategy = _truncationStrategy
        return if (strategy is SummaryTruncationStrategy) {
            strategy.getSummaries()
        } else {
            emptyList()
        }
    }

    override suspend fun loadSummaries(summaries: List<ConversationSummary>) {
        val strategy = _truncationStrategy
        if (strategy is SummaryTruncationStrategy) {
            strategy.loadSummaries(summaries)
        }
    }

    // ==================== Facts ====================

    override suspend fun getFacts(): List<Fact> {
        val strategy = _truncationStrategy
        return if (strategy is StickyFactsStrategy) strategy.getFacts() else emptyList()
    }

    override suspend fun refreshFacts(): List<Fact> {
        val strategy = _truncationStrategy
        return if (strategy is StickyFactsStrategy) {
            strategy.refreshFacts(_context.getHistory())
        } else {
            emptyList()
        }
    }

    override suspend fun loadFacts(facts: List<Fact>) {
        val strategy = _truncationStrategy
        if (strategy is StickyFactsStrategy) strategy.loadFacts(facts)
    }

    // ==================== Branches ====================

    override suspend fun getBranches(): List<DialogBranch> {
        val strategy = _truncationStrategy
        return if (strategy is BranchingStrategy) strategy.getBranches() else emptyList()
    }

    override suspend fun getActiveBranchId(): String? {
        val strategy = _truncationStrategy
        return if (strategy is BranchingStrategy) strategy.getActiveBranchId() else null
    }

    override suspend fun createCheckpoint(): DialogBranch? {
        val strategy = _truncationStrategy
        if (strategy !is BranchingStrategy) return null
        val newBranch = strategy.createCheckpoint(
            currentHistory = _context.getHistory(),
            currentSummaries = emptyList()
        )
        return newBranch
    }

    override suspend fun switchToBranch(branchId: String): Boolean {
        val strategy = _truncationStrategy
        if (strategy !is BranchingStrategy) return false

        val branch = strategy.switchToBranch(
            branchId = branchId,
            currentHistory = _context.getHistory(),
            currentSummaries = emptyList()
        ) ?: return false

        _context.replaceHistory(branch.messages)
        return true
    }

    override suspend fun initBranches() {
        val strategy = _truncationStrategy
        if (strategy !is BranchingStrategy) return
        val activeId = strategy.ensureInitialized()
        // Загружаем историю активной ветки
        val branches = strategy.getBranches()
        val activeBranch = branches.find { it.id == activeId }
        if (activeBranch != null && _context.isEmpty) {
            _context.replaceHistory(activeBranch.messages)
        }
    }

    // ==================== Core ====================

    override suspend fun chat(request: AgentRequest): AgentResponse {
        return withContext(Dispatchers.IO) {
            if (_config.keepConversationHistory) {
                addMessageWithTruncation(AgentMessage(role = Role.USER, content = request.userMessage))
            }
            validateRequest(request)

            val chatRequest = buildChatRequest(request)
            val responseBuilder = StringBuilder()
            var finalStats: TokenStats? = null
            var finalDuration: Long? = null

            api.sendMessageStream(chatRequest).collect { result ->
                when (result) {
                    is StatsStreamResult.Content -> responseBuilder.append(result.text)
                    is StatsStreamResult.Stats -> {
                        finalStats = result.tokenStats
                        finalDuration = result.durationMs
                    }
                }
            }

            val responseContent = responseBuilder.toString()

            if (_config.keepConversationHistory) {
                addMessageWithTruncation(AgentMessage(role = Role.ASSISTANT, content = responseContent))
            }

            AgentResponse(
                content = responseContent,
                tokenStats = finalStats,
                durationMs = finalDuration,
                model = request.model
            )
        }
    }

    override suspend fun chatStream(request: AgentRequest): Flow<AgentStreamEvent> {
        if (_config.keepConversationHistory) {
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
                        if (_config.keepConversationHistory && responseBuilder.isNotEmpty()) {
                            addMessageWithTruncation(
                                AgentMessage(role = Role.ASSISTANT, content = responseBuilder.toString())
                            )
                        }
                        AgentStreamEvent.Completed(
                            tokenStats = result.tokenStats,
                            durationMs = result.durationMs
                        )
                    }
                }
            }
            .catch { e -> emit(AgentStreamEvent.Error(wrapException(e))) }
    }

    override suspend fun send(message: String): Flow<AgentStreamEvent> {
        val request = AgentRequest(
            userMessage = message,
            systemPrompt = _config.defaultSystemPrompt,
            model = _config.defaultModel,
            temperature = _config.defaultTemperature,
            maxTokens = _config.defaultMaxTokens,
            stopSequences = _config.defaultStopSequences
        )
        return chatStream(request)
    }

    override suspend fun clearHistory() {
        _context.clear()
        val strategy = _truncationStrategy
        when (strategy) {
            is SummaryTruncationStrategy -> strategy.clearSummaries()
            is StickyFactsStrategy -> strategy.clearFacts()
            is BranchingStrategy -> strategy.clearBranches()
            else -> Unit
        }
    }

    override suspend fun addToHistory(message: AgentMessage) {
        addMessageWithTruncation(message)
    }

    /**
     * Обновляет конфигурацию агента.
     * `synchronized` здесь безопасен — метод не suspend, приостановок нет.
     */
    override fun updateConfig(newConfig: AgentConfig) {
        synchronized(this) { _config = newConfig }
    }

    /**
     * Обновляет стратегию обрезки контекста.
     * `synchronized` здесь безопасен — метод не suspend, приостановок нет.
     */
    override fun updateTruncationStrategy(strategy: ContextTruncationStrategy?) {
        synchronized(this) { _truncationStrategy = strategy }
    }

    // ==================== Private ====================

    private suspend fun addMessageWithTruncation(message: AgentMessage) {
        _context.addMessage(message)
        applyTruncation()
    }

    private suspend fun applyTruncation(): Unit = withContext(Dispatchers.IO) {
        val strategy = _truncationStrategy ?: return@withContext
        val history = _context.getHistory()
        if (history.isEmpty()) return@withContext

        val truncated = strategy.truncate(
            messages = history,
            maxTokens = _config.maxTokens,
            maxMessages = _config.maxHistorySize
        )

        if (truncated.size != history.size) {
            _context.replaceHistory(truncated)
        }
    }

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

    private suspend fun buildChatRequest(request: AgentRequest): ChatRequest {
        return ChatRequest(
            messages = buildMessageList(request),
            model = request.model,
            temperature = request.temperature,
            max_tokens = request.maxTokens,
            stop = request.stopSequences ?: _config.defaultStopSequences
        )
    }

    /**
     * Формирует список сообщений для API.
     *
     * Структура:
     * 1. System prompt (если есть)
     * 2. Дополнительные системные сообщения от стратегии (summary / facts)
     * 3a. keepConversationHistory=true  → _context.getHistory() (уже включает userMessage)
     * 3b. keepConversationHistory=false → только текущий userMessage
     */
    private suspend fun buildMessageList(request: AgentRequest): List<ApiMessage> {
        val messages = mutableListOf<ApiMessage>()

        // 1. Системный промпт
        val systemPrompt = request.systemPrompt ?: _config.defaultSystemPrompt
        if (!systemPrompt.isNullOrBlank()) {
            messages.add(ApiMessage(role = "system", content = systemPrompt))
        }

        // 2. Дополнительные системные сообщения от стратегии (summary, facts и т.п.)
        _truncationStrategy?.getAdditionalSystemMessages()
            ?.forEach { msg -> messages.add(msg.toApiMessage()) }

        // 3. История или одиночный запрос
        if (_config.keepConversationHistory) {
            _context.getHistory().forEach { msg -> messages.add(msg.toApiMessage()) }
        } else {
            messages.add(ApiMessage(role = "user", content = request.userMessage))
        }

        return messages
    }

    private fun wrapException(e: Throwable): AgentException = when (e) {
        is AgentException -> e
        is SocketTimeoutException -> AgentException.TimeoutError("Request timed out: ${e.message}", e)
        else -> AgentException.ApiError(message = e.message ?: "Unknown error", cause = e)
    }

    private fun AgentMessage.toApiMessage(): ApiMessage {
        val roleString = when (role) {
            Role.USER -> "user"
            Role.ASSISTANT -> "assistant"
            Role.SYSTEM -> "system"
        }
        return ApiMessage(role = roleString, content = content)
    }
}
