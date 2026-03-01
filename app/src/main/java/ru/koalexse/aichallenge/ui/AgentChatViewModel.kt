package ru.koalexse.aichallenge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.koalexse.aichallenge.agent.Agent
import ru.koalexse.aichallenge.agent.AgentMessage
import ru.koalexse.aichallenge.agent.AgentStreamEvent
import ru.koalexse.aichallenge.agent.context.branch.DialogBranch
import ru.koalexse.aichallenge.agent.context.facts.Fact
import ru.koalexse.aichallenge.agent.context.strategy.BranchingStrategy
import ru.koalexse.aichallenge.agent.context.strategy.ContextTruncationStrategy
import ru.koalexse.aichallenge.agent.context.strategy.StickyFactsStrategy
import ru.koalexse.aichallenge.agent.context.strategy.SummaryTruncationStrategy
import ru.koalexse.aichallenge.agent.context.summary.ConversationSummary
import ru.koalexse.aichallenge.data.persistence.ChatHistoryMapper.toMessages
import ru.koalexse.aichallenge.data.persistence.ChatHistoryMapper.toSession
import ru.koalexse.aichallenge.data.persistence.ChatHistoryMapper.toSessionTokenStats
import ru.koalexse.aichallenge.data.persistence.ChatHistoryMapper.toSummaries
import ru.koalexse.aichallenge.domain.Message
import ru.koalexse.aichallenge.domain.SessionTokenStats
import ru.koalexse.aichallenge.domain.TokenStats
import ru.koalexse.aichallenge.ui.state.ChatUiState
import ru.koalexse.aichallenge.ui.state.ContextStrategyType
import ru.koalexse.aichallenge.ui.state.SettingsData
import ru.koalexse.aichallenge.ui.state.toSettingsData
import java.util.UUID

class AgentChatViewModel(
    private val agent: Agent,
    private val availableModels: List<String>,
    private val chatHistoryRepository: ru.koalexse.aichallenge.data.persistence.ChatHistoryRepository? = null,
    initialStrategy: ContextStrategyType = ContextStrategyType.SUMMARY,
    /**
     * Фабрика стратегий — используется при смене стратегии в настройках.
     * Принимает [ContextStrategyType], возвращает готовую стратегию.
     * Инжектируется из AppModule, чтобы ViewModel не зависела от Android Context напрямую.
     */
    private val strategyFactory: ((ContextStrategyType) -> ContextTruncationStrategy?)? = null
) : ViewModel() {

    private data class StreamingMessage(
        val text: String = "",
        val tokenStats: TokenStats? = null,
        val responseDurationMs: Long? = null
    )

    private data class InternalState(
        val currentSessionId: String,
        val currentInput: String = "",
        val isLoading: Boolean = false,
        val isSettingsOpen: Boolean = false,
        val error: String? = null,
        val settingsData: SettingsData,
        val streamingMessage: StreamingMessage? = null,
        val lastMessageStats: TokenStats? = null,
        val lastMessageDuration: Long? = null,
        val isHistoryLoading: Boolean = true,
        val sessionStats: SessionTokenStats = SessionTokenStats(),
        val summaries: List<ConversationSummary> = emptyList(),
        // Facts
        val facts: List<Fact> = emptyList(),
        val isRefreshingFacts: Boolean = false,
        /**
         * Сообщения, вытесненные из LLM-контекста стратегией [StickyFactsStrategy].
         * Используются только для отображения в UI с пометкой «сжатые» —
         * в LLM-запрос не включаются.
         */
        val factsCompressedMessages: List<AgentMessage> = emptyList(),
        // Branches
        val branches: List<DialogBranch> = emptyList(),
        val activeBranchId: String? = null,
        val isBranchLimitReached: Boolean = false,
        val isSwitchingBranch: Boolean = false,
        val isBranchDialogOpen: Boolean = false,
        // Strategy
        val activeStrategy: ContextStrategyType
    )

    private var currentSessionId: String = UUID.randomUUID().toString()
    private var sessionCreatedAt: Long = System.currentTimeMillis()

    private val _internalState = MutableStateFlow(
        InternalState(
            currentSessionId = currentSessionId,
            settingsData = agent.config.toSettingsData(initialStrategy),
            activeStrategy = initialStrategy
        )
    )

    private val _state = MutableStateFlow(buildUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _internalState.collect { _state.value = buildUiState() }
        }
        viewModelScope.launch { loadSavedHistory() }
    }

    // ==================== Capability accessors ====================

    /** Доступ к summary-стратегии без знания конкретного типа в остальном коде. */
    private val summaryStrategy: SummaryTruncationStrategy?
        get() = agent.truncationStrategy as? SummaryTruncationStrategy

    /** Доступ к facts-стратегии без знания конкретного типа в остальном коде. */
    private val factsStrategy: StickyFactsStrategy?
        get() = agent.truncationStrategy as? StickyFactsStrategy

    /** Доступ к branching-стратегии без знания конкретного типа в остальном коде. */
    private val branchingStrategy: BranchingStrategy?
        get() = agent.truncationStrategy as? BranchingStrategy

    // ==================== UI State ====================

    private fun buildUiState(): ChatUiState {
        val internal = _internalState.value

        val compressedMessages = internal.summaries.toCompressedUiMessages()

        // Compressed-сообщения StickyFactsStrategy — вытеснены из LLM-контекста,
        // показываются в UI с пометкой «сжатые», в LLM не идут
        val factsCompressedMessages =
            if (internal.activeStrategy == ContextStrategyType.STICKY_FACTS)
                internal.factsCompressedMessages.toFactsCompressedUiMessages()
            else
                emptyList()

        val historyMessages = agent.conversationHistory.toActiveUiMessages(
            lastMessageStats = internal.lastMessageStats,
            lastMessageDuration = internal.lastMessageDuration
        )
        val streamingMessages = listOfNotNull(internal.streamingMessage?.toUiMessage())

        // Факты — отдельный бабл в начале ленты
        val factsMessages = if (
            internal.activeStrategy == ContextStrategyType.STICKY_FACTS &&
            internal.facts.isNotEmpty()
        ) listOf(buildFactsMessage(internal.facts)) else emptyList()

        return ChatUiState(
            messages = factsMessages + factsCompressedMessages + compressedMessages + historyMessages + streamingMessages,
            availableModels = availableModels,
            settingsData = internal.settingsData,
            currentInput = internal.currentInput,
            isLoading = internal.isLoading || internal.isHistoryLoading || internal.isSwitchingBranch,
            isSettingsOpen = internal.isSettingsOpen,
            error = internal.error,
            sessionStats = internal.sessionStats.takeIf { it.messageCount > 0 },
            compressedMessageCount = compressedMessages.size + factsCompressedMessages.size,
            activeStrategy = internal.activeStrategy,
            facts = internal.facts,
            isRefreshingFacts = internal.isRefreshingFacts,
            branches = internal.branches,
            activeBranchId = internal.activeBranchId,
            isBranchLimitReached = internal.isBranchLimitReached,
            isSwitchingBranch = internal.isSwitchingBranch,
            isBranchDialogOpen = internal.isBranchDialogOpen
        )
    }

    private fun buildFactsMessage(facts: List<Fact>): Message {
        val text = facts.joinToString("\n") { "• ${it.key}: ${it.value}" }
        return Message(id = "facts_bubble", isUser = false, text = text, isCompressed = true)
    }

    // ==================== Intents ====================

    fun handleIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.UpdateInput ->
                _internalState.update { it.copy(currentInput = intent.text) }

            is ChatIntent.SendMessage -> sendMessage(intent.text)
            is ChatIntent.ClearError  -> _internalState.update { it.copy(error = null) }
            ChatIntent.OpenSettings   -> _internalState.update { it.copy(isSettingsOpen = !it.isSettingsOpen) }
            is ChatIntent.SaveSettings -> handleSettingsUpdate(intent.settingsData)
            ChatIntent.ClearSession   -> clearSession()
            ChatIntent.RefreshFacts   -> refreshFacts()
            ChatIntent.CreateCheckpoint -> createCheckpoint()
            ChatIntent.OpenBranchDialog ->
                _internalState.update { it.copy(isBranchDialogOpen = !it.isBranchDialogOpen) }
            is ChatIntent.SwitchBranch -> switchBranch(intent.branchId)
        }
    }

    // ==================== Send ====================

    private fun sendMessage(text: String) {
        _internalState.update { it.copy(currentInput = "", error = null, isLoading = true) }
        viewModelScope.launch { handleAgentStream(text) }
    }

    private suspend fun handleAgentStream(userText: String) {
        agent.send(userText).also {
            _internalState.update {
                it.copy(streamingMessage = StreamingMessage(), lastMessageStats = null, lastMessageDuration = null)
            }
        }.onEach { event ->
            when (event) {
                is AgentStreamEvent.ContentDelta ->
                    _internalState.update { state ->
                        state.copy(
                            streamingMessage = state.streamingMessage?.copy(
                                text = state.streamingMessage.text + event.text
                            )
                        )
                    }

                is AgentStreamEvent.Completed -> {
                    // Читаем state стратегий через capability accessors
                    val newSummaries = summaryStrategy?.getSummaries() ?: emptyList()
                    val newBranches  = agent.getBranches()
                    val newActiveId  = agent.getActiveBranchId()
                    // После каждого ответа синхронизируем compressed-сообщения StickyFacts —
                    // truncate() мог добавить новые вытесненные сообщения в factsStorage
                    val newFactsCompressed = factsStrategy?.getCompressedMessages() ?: emptyList()
                    _internalState.update { state ->
                        state.copy(
                            streamingMessage = state.streamingMessage?.copy(
                                tokenStats = event.tokenStats,
                                responseDurationMs = event.durationMs
                            ),
                            sessionStats = state.sessionStats.add(event.tokenStats),
                            summaries = newSummaries,
                            factsCompressedMessages = newFactsCompressed,
                            branches = newBranches,
                            activeBranchId = newActiveId
                        )
                    }
                }

                is AgentStreamEvent.Error -> handleError(event.exception)
            }
        }
            .onCompletion { error ->
                if (error == null) {
                    val streamingMsg = _internalState.value.streamingMessage
                    _internalState.update { state ->
                        state.copy(
                            isLoading = false,
                            streamingMessage = null,
                            lastMessageStats = streamingMsg?.tokenStats,
                            lastMessageDuration = streamingMsg?.responseDurationMs
                        )
                    }
                    saveHistory()
                }
            }
            .catch { error -> handleError(error) }
            .collect()
    }

    private fun handleError(error: Throwable) {
        _internalState.update {
            it.copy(isLoading = false, streamingMessage = null, error = error.message ?: "Unknown error")
        }
    }

    // ==================== Settings & Strategy switch ====================

    private fun handleSettingsUpdate(settingsData: SettingsData) {
        val oldStrategy = _internalState.value.activeStrategy
        val newStrategy = settingsData.strategy

        val temperature = runCatching { settingsData.temperature?.toFloat() }.getOrNull()
        val tokens      = runCatching { settingsData.tokens?.toLong() }.getOrNull()

        agent.updateConfig(
            agent.config.copy(
                defaultModel = settingsData.model,
                defaultTemperature = temperature,
                defaultMaxTokens = tokens
            )
        )

        _internalState.update {
            it.copy(settingsData = settingsData, isSettingsOpen = false, activeStrategy = newStrategy)
        }

        if (newStrategy != oldStrategy && strategyFactory != null) {
            viewModelScope.launch { applyStrategyChange(newStrategy) }
        } else {
            viewModelScope.launch { saveHistory() }
        }
    }

    /**
     * Применяет смену стратегии:
     * 1. Устанавливаем новую стратегию на агенте (история в контексте сохраняется)
     * 2. Сбрасываем UI-данные предыдущей стратегии
     * 3. Инициализируем специфичные данные новой стратегии через capability accessors
     */
    private suspend fun applyStrategyChange(newStrategyType: ContextStrategyType) {
        val factory = strategyFactory ?: return

        agent.updateTruncationStrategy(factory(newStrategyType))

        _internalState.update { state ->
            state.copy(
                summaries = emptyList(),
                facts = emptyList(),
                factsCompressedMessages = emptyList(),
                branches = emptyList(),
                activeBranchId = null,
                isBranchLimitReached = false,
                streamingMessage = null,
                lastMessageStats = null,
                lastMessageDuration = null
            )
        }

        when (newStrategyType) {
            ContextStrategyType.BRANCHING -> {
                agent.initBranches()
                val branches = agent.getBranches()
                val activeId = agent.getActiveBranchId()
                _internalState.update {
                    it.copy(
                        branches = branches,
                        activeBranchId = activeId,
                        isBranchLimitReached = branches.size >= BranchingStrategy.MAX_BRANCHES
                    )
                }
            }

            ContextStrategyType.STICKY_FACTS -> {
                // Capability accessor — нет знания о конкретном типе в логике
                val savedFacts = factsStrategy?.getFacts() ?: emptyList()
                val savedCompressed = factsStrategy?.getCompressedMessages() ?: emptyList()
                _internalState.update {
                    it.copy(
                        facts = savedFacts,
                        factsCompressedMessages = savedCompressed
                    )
                }
            }

            ContextStrategyType.SUMMARY -> {
                val savedSummaries = summaryStrategy?.getSummaries() ?: emptyList()
                _internalState.update { it.copy(summaries = savedSummaries) }
            }

            ContextStrategyType.SLIDING_WINDOW -> { /* ничего специфичного */ }
        }

        saveHistory()
    }

    // ==================== Clear ====================

    private fun clearSession() {
        viewModelScope.launch {
            agent.clearHistory()
            currentSessionId = UUID.randomUUID().toString()
            sessionCreatedAt = System.currentTimeMillis()

            val newBranches: List<DialogBranch>
            val newActiveBranchId: String?
            if (_internalState.value.activeStrategy == ContextStrategyType.BRANCHING) {
                agent.initBranches()
                newBranches       = agent.getBranches()
                newActiveBranchId = agent.getActiveBranchId()
            } else {
                newBranches       = emptyList()
                newActiveBranchId = null
            }

            _internalState.update {
                it.copy(
                    currentSessionId        = currentSessionId,
                    streamingMessage        = null,
                    lastMessageStats        = null,
                    lastMessageDuration     = null,
                    error                   = null,
                    sessionStats            = SessionTokenStats(),
                    summaries               = emptyList(),
                    facts                   = emptyList(),
                    factsCompressedMessages = emptyList(),
                    branches                = newBranches,
                    activeBranchId          = newActiveBranchId,
                    isBranchLimitReached    = false
                )
            }
            chatHistoryRepository?.clearAll()
        }
    }

    // ==================== Facts ====================

    private fun refreshFacts() {
        if (_internalState.value.isRefreshingFacts) return
        _internalState.update { it.copy(isRefreshingFacts = true, error = null) }
        viewModelScope.launch {
            try {
                // agent.conversationHistory содержит только recent-сообщения —
                // вытесненные уже в factsStorage и учтены в существующих фактах
                val updatedFacts = factsStrategy?.refreshFacts(agent.conversationHistory)
                    ?: emptyList()
                // Compressed-сообщения не меняются при refresh — читаем актуальные
                val compressed = factsStrategy?.getCompressedMessages() ?: emptyList()
                _internalState.update {
                    it.copy(
                        facts = updatedFacts,
                        factsCompressedMessages = compressed,
                        isRefreshingFacts = false
                    )
                }
                saveHistory()
            } catch (e: Exception) {
                _internalState.update {
                    it.copy(isRefreshingFacts = false, error = e.message ?: "Failed to refresh facts")
                }
            }
        }
    }

    // ==================== Branches ====================

    private fun createCheckpoint() {
        viewModelScope.launch {
            val newBranch = agent.createCheckpoint()
            if (newBranch == null) {
                _internalState.update { it.copy(isBranchLimitReached = true) }
                return@launch
            }
            val branches       = agent.getBranches()
            val activeBranchId = agent.getActiveBranchId()
            _internalState.update {
                it.copy(
                    branches             = branches,
                    activeBranchId       = activeBranchId,
                    isBranchLimitReached = branches.size >= BranchingStrategy.MAX_BRANCHES
                )
            }
            saveHistory()
        }
    }

    private fun switchBranch(branchId: String) {
        if (_internalState.value.activeBranchId == branchId) {
            _internalState.update { it.copy(isBranchDialogOpen = false) }
            return
        }
        _internalState.update { it.copy(isSwitchingBranch = true, isBranchDialogOpen = false) }
        viewModelScope.launch {
            try {
                val success = agent.switchToBranch(branchId)
                if (success) {
                    val branches          = agent.getBranches()
                    val newActiveBranchId = agent.getActiveBranchId()
                    _internalState.update {
                        it.copy(
                            isSwitchingBranch   = false,
                            branches            = branches,
                            activeBranchId      = newActiveBranchId,
                            streamingMessage    = null,
                            lastMessageStats    = null,
                            lastMessageDuration = null
                        )
                    }
                } else {
                    _internalState.update { it.copy(isSwitchingBranch = false) }
                }
            } catch (e: Exception) {
                _internalState.update {
                    it.copy(isSwitchingBranch = false, error = e.message ?: "Failed to switch branch")
                }
            }
        }
    }

    // ==================== Persistence ====================

    private suspend fun loadSavedHistory() {
        if (chatHistoryRepository == null) {
            _internalState.update { it.copy(isHistoryLoading = false) }
            if (_internalState.value.activeStrategy == ContextStrategyType.BRANCHING) {
                agent.initBranches()
                val branches = agent.getBranches()
                val activeId = agent.getActiveBranchId()
                _internalState.update {
                    it.copy(
                        branches             = branches,
                        activeBranchId       = activeId,
                        isBranchLimitReached = branches.size >= BranchingStrategy.MAX_BRANCHES
                    )
                }
            }
            return
        }

        try {
            val session = chatHistoryRepository.loadActiveSession()

            if (session != null && session.messages.isNotEmpty()) {
                currentSessionId = session.id
                sessionCreatedAt = session.createdAt

                val savedSummaries = session.toSummaries()
                if (savedSummaries.isNotEmpty()) {
                    summaryStrategy?.loadSummaries(savedSummaries)
                }

                session.toMessages().forEach { agent.addToHistory(it) }

                session.model?.let { model ->
                    if (model in availableModels) {
                        agent.updateConfig(agent.config.copy(defaultModel = model))
                        _internalState.update { state ->
                            state.copy(settingsData = state.settingsData.copy(model = model))
                        }
                    }
                }

                _internalState.update { state ->
                    state.copy(
                        sessionStats = session.toSessionTokenStats() ?: SessionTokenStats(),
                        summaries    = savedSummaries
                    )
                }
            }

            // Загружаем факты и compressed-сообщения через capability accessor.
            // JsonFactsStorage уже восстановил оба списка из facts.json при первом обращении.
            val savedFacts = factsStrategy?.getFacts() ?: emptyList()
            val savedCompressed = factsStrategy?.getCompressedMessages() ?: emptyList()
            if (savedFacts.isNotEmpty() || savedCompressed.isNotEmpty()) {
                _internalState.update {
                    it.copy(
                        facts = savedFacts,
                        factsCompressedMessages = savedCompressed
                    )
                }
            }

            if (_internalState.value.activeStrategy == ContextStrategyType.BRANCHING) {
                agent.initBranches()
                val branches = agent.getBranches()
                val activeId = agent.getActiveBranchId()
                _internalState.update {
                    it.copy(
                        branches             = branches,
                        activeBranchId       = activeId,
                        isBranchLimitReached = branches.size >= BranchingStrategy.MAX_BRANCHES
                    )
                }
            }
        } catch (e: Exception) {
            println(e)
        } finally {
            _internalState.update { it.copy(isHistoryLoading = false) }
        }
    }

    private suspend fun saveHistory() {
        if (chatHistoryRepository == null) return

        if (_internalState.value.activeStrategy == ContextStrategyType.BRANCHING) {
            branchingStrategy?.saveActiveBranch(
                currentHistory   = agent.conversationHistory,
                currentSummaries = emptyList()
            )
            return
        }

        val history = agent.conversationHistory
        if (history.isEmpty()) return

        try {
            val session = history.toSession(
                sessionId    = currentSessionId,
                model        = agent.config.defaultModel,
                createdAt    = sessionCreatedAt,
                sessionStats = _internalState.value.sessionStats.takeIf { it.messageCount > 0 },
                summaries    = summaryStrategy?.getSummaries() ?: emptyList()
            )
            chatHistoryRepository.saveSession(session)
            chatHistoryRepository.setActiveSession(currentSessionId)
        } catch (e: Exception) {
            println(e)
        }
    }

    // ==================== Helpers ====================

    private fun StreamingMessage.toUiMessage(): Message = Message(
        id                 = "streaming_msg",
        isUser             = false,
        text               = text,
        isLoading          = true,
        tokenStats         = tokenStats,
        responseDurationMs = responseDurationMs
    )
}

sealed class ChatIntent {
    data class UpdateInput(val text: String) : ChatIntent()
    data class SendMessage(val text: String) : ChatIntent()
    data object ClearError : ChatIntent()
    data object ClearSession : ChatIntent()
    data object OpenSettings : ChatIntent()
    data class SaveSettings(val settingsData: SettingsData) : ChatIntent()
    data object RefreshFacts : ChatIntent()
    data object CreateCheckpoint : ChatIntent()
    data object OpenBranchDialog : ChatIntent()
    data class SwitchBranch(val branchId: String) : ChatIntent()
}
