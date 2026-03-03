package ru.koalexse.aichallenge.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import ru.koalexse.aichallenge.agent.context.AgentContext
import ru.koalexse.aichallenge.agent.context.SimpleAgentContext
import ru.koalexse.aichallenge.agent.context.branch.DialogBranch
import ru.koalexse.aichallenge.agent.context.strategy.BranchingStrategy
import ru.koalexse.aichallenge.agent.context.strategy.ContextTruncationStrategy
import ru.koalexse.aichallenge.agent.profile.ProfileSystemPromptProvider
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
 * - Инкапсулирует историю — снаружи только read-only доступ через [conversationHistory]
 * - Поддерживает стратегии управления контекстом через [ContextTruncationStrategy]
 * - Поддерживает персонализацию через [ProfileSystemPromptProvider]
 * - Не зависит от Android фреймворка
 *
 * ### Доступ к возможностям стратегий
 * Стратегия-специфичные операции (summaries, facts, branches) доступны
 * через приведение типа [truncationStrategy]:
 * ```kotlin
 * (agent.truncationStrategy as? SummaryTruncationStrategy)?.getSummaries()
 * (agent.truncationStrategy as? StickyFactsStrategy)?.getFacts()
 * (agent.truncationStrategy as? BranchingStrategy)?.getBranches()
 * ```
 *
 * ### Персонализация через профиль
 * При каждом запросе [profilePromptProvider] динамически читает активный профиль
 * и добавляет его факты первым блоком в system-промпт:
 * ```
 * ## User Profile
 * - факт 1
 * - факт 2
 *
 * <defaultSystemPrompt>
 *
 * ## Summary / Facts   ← от стратегии
 * ```
 * Если провайдер не задан или у профиля нет фактов — блок не добавляется.
 *
 * ### Потокобезопасность
 * - [_config] и [_truncationStrategy] помечены `@Volatile` — гарантирует видимость
 *   изменений между потоками без блокировки при чтении в suspend-функциях.
 * - Запись защищена `synchronized` только в не-suspend методах [updateConfig] и
 *   [updateTruncationStrategy], где приостановок корутины нет.
 * - [_context] ([SimpleAgentContext]) использует `synchronized` внутри.
 * - В каждом публичном методе делается **один** snapshot `val config = _config` —
 *   все дальнейшие обращения к конфигу идут через локальную переменную, исключая
 *   рассогласование при конкурентном [updateConfig].
 *
 * @param api интерфейс для работы с LLM API
 * @param initialConfig начальная конфигурация агента
 * @param agentContext контекст для управления историей (по умолчанию [SimpleAgentContext])
 * @param truncationStrategy стратегия обрезки контекста (null = без обрезки)
 * @param profilePromptProvider провайдер блока профиля для system-промпта (null = без профиля)
 */
class SimpleLLMAgent(
    private val api: StatsLLMApi,
    initialConfig: AgentConfig,
    agentContext: AgentContext = SimpleAgentContext(),
    truncationStrategy: ContextTruncationStrategy? = null,
    private val profilePromptProvider: ProfileSystemPromptProvider? = null
) : Agent {

    @Volatile
    private var _config: AgentConfig = initialConfig

    private val _context: AgentContext = agentContext

    @Volatile
    private var _truncationStrategy: ContextTruncationStrategy? = truncationStrategy

    override val config: AgentConfig
        get() = _config

    override val truncationStrategy: ContextTruncationStrategy?
        get() = _truncationStrategy

    override val conversationHistory: List<AgentMessage>
        get() = _context.getHistory()

    // ==================== Core ====================

    override suspend fun chat(request: AgentRequest): AgentResponse {
        // #10: validateRequest ПЕРВЫМ — до любой мутации _context.
        // Если сообщение невалидно — история не будет испорчена.
        validateRequest(request)

        // #2: единственное volatile-чтение _config для всего вызова.
        // Все дальнейшие обращения идут через локальный snapshot.
        val config = _config

        return withContext(Dispatchers.IO) {
            if (config.keepConversationHistory) {
                addMessageWithTruncation(
                    message = AgentMessage(role = Role.USER, content = request.userMessage),
                    config = config
                )
            }

            val chatRequest = buildChatRequest(request, config)
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

            if (config.keepConversationHistory) {
                addMessageWithTruncation(
                    message = AgentMessage(role = Role.ASSISTANT, content = responseContent),
                    config = config
                )
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
        // #10: validateRequest ПЕРВЫМ — до любой мутации _context.
        // Если сообщение невалидно — история не будет испорчена.
        validateRequest(request)

        // #2: единственное volatile-чтение _config для всего вызова,
        // включая замыкание внутри .map { } — конфиг не изменится в середине стрима.
        val config = _config

        if (config.keepConversationHistory) {
            addMessageWithTruncation(
                message = AgentMessage(role = Role.USER, content = request.userMessage),
                config = config
            )
        }

        val chatRequest = buildChatRequest(request, config)
        val responseBuilder = StringBuilder()

        return api.sendMessageStream(chatRequest)
            .map { result ->
                when (result) {
                    is StatsStreamResult.Content -> {
                        responseBuilder.append(result.text)
                        AgentStreamEvent.ContentDelta(result.text)
                    }

                    is StatsStreamResult.Stats -> {
                        if (config.keepConversationHistory && responseBuilder.isNotEmpty()) {
                            addMessageWithTruncation(
                                message = AgentMessage(
                                    role = Role.ASSISTANT,
                                    content = responseBuilder.toString()
                                ),
                                config = config
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
        // #2: единственное volatile-чтение — snapshot для всего вызова.
        // chatStream получит уже готовый AgentRequest и не будет читать _config сам.
        val config = _config
        val request = AgentRequest(
            userMessage = message,
            systemPrompt = config.defaultSystemPrompt,
            model = config.defaultModel,
            temperature = config.defaultTemperature,
            maxTokens = config.defaultMaxTokens,
            stopSequences = config.defaultStopSequences
        )
        return chatStream(request)
    }

    override suspend fun clearHistory() {
        _context.clear()
        // Делегируем очистку стратегии — не нужно знать её конкретный тип
        _truncationStrategy?.clear()
    }

    override suspend fun addToHistory(message: AgentMessage) {
        // Snapshot конфига для согласованного применения лимитов
        addMessageWithTruncation(message, config = _config)
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

    // ==================== Branches (делегирование через cast) ====================

    override suspend fun initBranches() {
        val strategy = _truncationStrategy as? BranchingStrategy ?: return
        val activeId = strategy.ensureInitialized()
        val branches = strategy.getBranches()
        val activeBranch = branches.find { it.id == activeId }
        if (activeBranch != null && _context.isEmpty) {
            _context.replaceHistory(activeBranch.messages)
        }
    }

    override suspend fun createCheckpoint(): DialogBranch? {
        val strategy = _truncationStrategy as? BranchingStrategy ?: return null
        return strategy.createCheckpoint(
            currentHistory = _context.getHistory(),
            currentSummaries = emptyList()
        )
    }

    override suspend fun switchToBranch(branchId: String): Boolean {
        val strategy = _truncationStrategy as? BranchingStrategy ?: return false
        val branch = strategy.switchToBranch(
            branchId = branchId,
            currentHistory = _context.getHistory(),
            currentSummaries = emptyList()
        ) ?: return false

        _context.replaceHistory(branch.messages)
        return true
    }

    override suspend fun getActiveBranchId(): String? =
        (_truncationStrategy as? BranchingStrategy)?.getActiveBranchId()

    override suspend fun getBranches(): List<DialogBranch> =
        (_truncationStrategy as? BranchingStrategy)?.getBranches() ?: emptyList()

    // ==================== Private ====================

    /**
     * Добавляет сообщение в контекст и применяет стратегию обрезки.
     *
     * Принимает [config] явно — избегает повторного volatile-чтения [_config]
     * в середине уже начатой операции (snapshot гарантирован вызывающим методом).
     */
    private suspend fun addMessageWithTruncation(message: AgentMessage, config: AgentConfig) {
        _context.addMessage(message)
        applyTruncation(config)
    }

    /**
     * Применяет стратегию обрезки к текущей истории.
     *
     * Принимает [config] явно — тот же snapshot, что использовался при добавлении
     * сообщения. Гарантирует согласованность лимитов внутри одной операции.
     */
    private suspend fun applyTruncation(config: AgentConfig): Unit = withContext(Dispatchers.IO) {
        val strategy = _truncationStrategy ?: return@withContext
        val history = _context.getHistory()
        if (history.isEmpty()) return@withContext

        val truncated = strategy.truncate(
            messages = history,
            maxTokens = config.maxContextTokens,
            maxMessages = config.maxHistorySize
        )

        if (truncated.size != history.size) {
            _context.replaceHistory(truncated)
        }
    }

    /**
     * Валидирует запрос перед любыми изменениями состояния агента.
     *
     * Намеренно вызывается ПЕРВЫМ в [chat] и [chatStream] — до [addMessageWithTruncation].
     * Это гарантирует, что при невалидном запросе история [_context] не будет изменена
     * (не останется user-сообщения без соответствующего ответа ассистента).
     */
    private fun validateRequest(request: AgentRequest) {
        if (request.userMessage.isBlank()) {
            throw AgentException.ValidationError("User message cannot be blank")
        }
        if (request.model.isBlank()) {
            throw AgentException.ValidationError("Model name cannot be blank")
        }
        request.temperature?.let {
            if (it !in 0f..2f) {
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
     * Собирает [ChatRequest] из [AgentRequest] и снимка конфигурации.
     *
     * Принимает [config] явно — не читает [_config] напрямую,
     * чтобы не создавать дополнительных volatile-точек вне snapshot.
     */
    private suspend fun buildChatRequest(request: AgentRequest, config: AgentConfig): ChatRequest {
        return ChatRequest(
            messages = buildMessageList(request, config),
            model = request.model,
            temperature = request.temperature,
            max_tokens = request.maxTokens,
            // stopSequences из request имеют приоритет; fallback — из снимка конфига
            stop = request.stopSequences ?: config.defaultStopSequences
        )
    }

    /**
     * Формирует список сообщений для API.
     *
     * Принимает [config] явно — не читает [_config] напрямую.
     *
     * Структура system-промпта (все блоки объединяются через "\n\n"):
     * 1а. Блок профиля пользователя от [profilePromptProvider] (если задан и facts не пусты)
     * 1б. System prompt из request или config.defaultSystemPrompt (если не пустой)
     * 1в. Дополнительные системные сообщения от стратегии (summary / facts)
     *
     * Если все блоки пусты — system-сообщение не добавляется.
     *
     * Далее:
     * 2а. keepConversationHistory=true  → _context.getHistory() (уже включает userMessage)
     * 2б. keepConversationHistory=false → только текущий userMessage
     */
    private suspend fun buildMessageList(
        request: AgentRequest,
        config: AgentConfig
    ): List<ApiMessage> {
        val messages = mutableListOf<ApiMessage>()
        val systemPrompts = mutableListOf<String>()

        // 1а. Блок профиля — первый, до системного промпта и стратегии
        profilePromptProvider?.getProfileBlock()
            ?.let { systemPrompts.add(it) }

        // 1б. Системный промпт: из запроса или из снимка конфига
        val systemPrompt = request.systemPrompt ?: config.defaultSystemPrompt
        if (!systemPrompt.isNullOrBlank()) {
            systemPrompts.add(systemPrompt)
        }

        // 1в. Дополнительные системные сообщения от стратегии (summary, facts и т.п.)
        _truncationStrategy?.getAdditionalSystemMessages()
            ?.map { it.content }
            ?.filter { it.isNotBlank() }
            ?.let { systemPrompts.addAll(it) }

        // 2. Добавляем объединённый system-промпт, если есть что добавлять
        if (systemPrompts.isNotEmpty()) {
            // Заголовки уже есть внутри каждого блока
            val combinedSystemPrompt = systemPrompts.joinToString("\n\n")
            messages.add(ApiMessage(role = "system", content = combinedSystemPrompt))
        }

        // 3. История или одиночный запрос
        if (config.keepConversationHistory) {
            // Исключаем старые system-сообщения из истории,
            // чтобы избежать дублирования инструкций
            _context.getHistory()
                .filter { it.role != Role.SYSTEM }
                .forEach { msg -> messages.add(msg.toApiMessage()) }
        } else {
            messages.add(ApiMessage(role = "user", content = request.userMessage))
        }

        return messages
    }

    private fun wrapException(e: Throwable): AgentException = when (e) {
        is AgentException -> e
        is SocketTimeoutException -> AgentException.TimeoutError(
            "Request timed out: ${e.message}",
            e
        )
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
