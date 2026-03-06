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
import ru.koalexse.aichallenge.agent.AgentMessage
import ru.koalexse.aichallenge.agent.AgentStreamEvent
import ru.koalexse.aichallenge.agent.ConfigurableAgent
import ru.koalexse.aichallenge.agent.context.branch.DialogBranch
import ru.koalexse.aichallenge.agent.context.facts.Fact
import ru.koalexse.aichallenge.agent.context.memory.MemoryEntry
import ru.koalexse.aichallenge.agent.context.strategy.BranchingStrategy
import ru.koalexse.aichallenge.agent.context.strategy.ContextTruncationStrategy
import ru.koalexse.aichallenge.agent.context.strategy.LayeredMemoryStrategy
import ru.koalexse.aichallenge.agent.context.strategy.StickyFactsStrategy
import ru.koalexse.aichallenge.agent.context.strategy.SummaryTruncationStrategy
import ru.koalexse.aichallenge.agent.context.summary.ConversationSummary
import ru.koalexse.aichallenge.agent.task.AdvancePhaseResult
import ru.koalexse.aichallenge.agent.task.PhaseInvariants
import ru.koalexse.aichallenge.agent.task.TaskPhase
import ru.koalexse.aichallenge.agent.task.TaskState
import ru.koalexse.aichallenge.agent.task.TaskStateMachineAgent
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
    private val agent: ConfigurableAgent,
    private val availableModels: List<String>,
    private val chatHistoryRepository: ru.koalexse.aichallenge.data.persistence.ChatHistoryRepository? = null,
    initialStrategy: ContextStrategyType = ContextStrategyType.SUMMARY,
    /**
     * Фабрика стратегий — используется при смене стратегии в настройках.
     * Принимает [ContextStrategyType], возвращает готовую стратегию.
     * Инжектируется из AppModule, чтобы ViewModel не зависела от Android Context напрямую.
     */
    private val strategyFactory: ((ContextStrategyType) -> ContextTruncationStrategy?)? = null,
    /**
     * Task State Machine агент — предоставляется из AppModule.
     * null = Planning mode недоступен.
     */
    private val taskStateMachineAgent: TaskStateMachineAgent? = null
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
        // Layered Memory
        val workingMemory: List<MemoryEntry> = emptyList(),
        val longTermMemory: List<MemoryEntry> = emptyList(),
        val memoryCompressedMessages: List<AgentMessage> = emptyList(),
        val isRefreshingWorkingMemory: Boolean = false,
        val isRefreshingLongTermMemory: Boolean = false,
        // Planning mode (Task State Machine)
        val isPlanningMode: Boolean = false,
        val taskState: TaskState? = null,
        val isValidatingTask: Boolean = false,
        val taskValidationError: String? = null,
        val isAdvancingPhase: Boolean = false,
        val isStartTaskDialogOpen: Boolean = false,
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

    /** Доступ к layered memory-стратегии без знания конкретного типа в остальном коде. */
    private val layeredMemoryStrategy: LayeredMemoryStrategy?
        get() = agent.truncationStrategy as? LayeredMemoryStrategy

    /**
     * Активный агент — [TaskStateMachineAgent] в Planning mode, иначе обычный [agent].
     * Planning mode и стратегия контекста независимы: TSM использует свой innerAgent,
     * который не конфликтует с [agent].
     */
    private val activeAgent: ConfigurableAgent
        get() = if (_internalState.value.isPlanningMode && taskStateMachineAgent != null)
            taskStateMachineAgent else agent

    // ==================== UI State ====================

    private fun buildUiState(): ChatUiState {
        val internal = _internalState.value

        val compressedMessages = internal.summaries.toCompressedUiMessages()

        // Compressed-сообщения StickyFactsStrategy
        val factsCompressedMessages =
            if (internal.activeStrategy == ContextStrategyType.STICKY_FACTS)
                internal.factsCompressedMessages.toFactsCompressedUiMessages()
            else
                emptyList()

        // Compressed-сообщения LayeredMemoryStrategy
        val memoryCompressedMessages =
            if (internal.activeStrategy == ContextStrategyType.LAYERED_MEMORY)
                internal.memoryCompressedMessages.toMemoryCompressedUiMessages()
            else
                emptyList()

        val historyMessages = activeAgent.conversationHistory.toActiveUiMessages(
            lastMessageStats = internal.lastMessageStats,
            lastMessageDuration = internal.lastMessageDuration
        )
        val streamingMessages = listOfNotNull(internal.streamingMessage?.toUiMessage())

        // Факты StickyFacts — bubble в начале ленты
        val factsMessages = if (
            internal.activeStrategy == ContextStrategyType.STICKY_FACTS &&
            internal.facts.isNotEmpty()
        ) listOf(buildFactsMessage(internal.facts)) else emptyList()

        // Layered Memory — два bubble: 🧠 Long-term сверху, 💼 Working под ним
        val longTermMemoryMessages = if (internal.activeStrategy == ContextStrategyType.LAYERED_MEMORY)
            listOfNotNull(internal.longTermMemory.toLongTermMemoryUiMessage())
        else emptyList()

        val workingMemoryMessages = if (internal.activeStrategy == ContextStrategyType.LAYERED_MEMORY)
            listOfNotNull(internal.workingMemory.toWorkingMemoryUiMessage())
        else emptyList()

        // Planning mode — Task State bubble: только если режим активен и задача существует
        val taskStateMessages = if (internal.isPlanningMode)
            listOfNotNull(internal.taskState?.toTaskStateBubbleMessage())
        else
            emptyList()

        // Порядок сверху вниз в ленте (список перевёрнут в LazyColumn с reverseLayout):
        // task-state → long-term → working → memory-compressed → facts-compressed → summaries-compressed → history → streaming
        val allMessages =
            taskStateMessages +
            longTermMemoryMessages +
            workingMemoryMessages +
            memoryCompressedMessages +
            factsMessages +
            factsCompressedMessages +
            compressedMessages +
            historyMessages +
            streamingMessages

        return ChatUiState(
            messages = allMessages,
            availableModels = availableModels,
            settingsData = internal.settingsData,
            currentInput = internal.currentInput,
            isLoading = internal.isLoading || internal.isHistoryLoading || internal.isSwitchingBranch,
            isSettingsOpen = internal.isSettingsOpen,
            error = internal.error,
            sessionStats = internal.sessionStats.takeIf { it.messageCount > 0 },
            compressedMessageCount = compressedMessages.size + factsCompressedMessages.size + memoryCompressedMessages.size,
            activeStrategy = internal.activeStrategy,
            isPlanningMode = internal.isPlanningMode,
            facts = internal.facts,
            isRefreshingFacts = internal.isRefreshingFacts,
            branches = internal.branches,
            activeBranchId = internal.activeBranchId,
            isBranchLimitReached = internal.isBranchLimitReached,
            isSwitchingBranch = internal.isSwitchingBranch,
            isBranchDialogOpen = internal.isBranchDialogOpen,
            workingMemory = internal.workingMemory,
            longTermMemory = internal.longTermMemory,
            memoryCompressedMessages = internal.memoryCompressedMessages,
            isRefreshingWorkingMemory = internal.isRefreshingWorkingMemory,
            isRefreshingLongTermMemory = internal.isRefreshingLongTermMemory,
            // Task State Machine
            taskState = internal.taskState,
            isValidatingTask = internal.isValidatingTask,
            taskValidationError = internal.taskValidationError,
            isAdvancingPhase = internal.isAdvancingPhase,
            isStartTaskDialogOpen = internal.isStartTaskDialogOpen
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
            // Layered Memory
            ChatIntent.RefreshWorkingMemory  -> refreshWorkingMemory()
            ChatIntent.RefreshLongTermMemory -> refreshLongTermMemory()
            ChatIntent.ClearAllMemory        -> clearAllMemory()
            // Task State Machine
            ChatIntent.OpenStartTaskDialog ->
                _internalState.update { it.copy(isStartTaskDialogOpen = true) }
            ChatIntent.CloseStartTaskDialog ->
                _internalState.update { it.copy(isStartTaskDialogOpen = false) }
            is ChatIntent.StartTask    -> startTask(intent.phaseInvariants)
            ChatIntent.AdvancePhase    -> advancePhase()
            ChatIntent.ResetTask       -> resetTask()
            ChatIntent.ClearTaskError  ->
                _internalState.update { it.copy(taskValidationError = null) }
        }
    }

    // ==================== Send ====================

    private fun sendMessage(text: String) {
        _internalState.update { it.copy(currentInput = "", error = null, isLoading = true) }
        viewModelScope.launch { handleAgentStream(text) }
    }

    private suspend fun handleAgentStream(userText: String) {
        activeAgent.send(userText).also {
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
                    val newSummaries = summaryStrategy?.getSummaries() ?: emptyList()
                    val newBranches  = activeAgent.getBranches()
                    val newActiveId  = activeAgent.getActiveBranchId()
                    // StickyFacts — синхронизация после авторефреша в truncate()
                    val newFacts           = factsStrategy?.getFacts() ?: emptyList()
                    val newFactsCompressed = factsStrategy?.getCompressedMessages() ?: emptyList()
                    // LayeredMemory — синхронизация после авторефреша WORKING в truncate()
                    val newWorking            = layeredMemoryStrategy?.getWorkingMemory() ?: emptyList()
                    val newLongTerm           = layeredMemoryStrategy?.getLongTermMemory() ?: emptyList()
                    val newMemoryCompressed   = layeredMemoryStrategy?.getCompressedMessages() ?: emptyList()
                    // Planning mode — синхронизация состояния задачи после ответа
                    val newTaskState = if (_internalState.value.isPlanningMode)
                        taskStateMachineAgent?.getTaskState()
                    else null

                    _internalState.update { state ->
                        state.copy(
                            streamingMessage = state.streamingMessage?.copy(
                                tokenStats = event.tokenStats,
                                responseDurationMs = event.durationMs
                            ),
                            sessionStats              = state.sessionStats.add(event.tokenStats),
                            summaries                 = newSummaries,
                            facts                     = newFacts,
                            factsCompressedMessages   = newFactsCompressed,
                            branches                  = newBranches,
                            activeBranchId            = newActiveId,
                            workingMemory             = newWorking,
                            longTermMemory            = newLongTerm,
                            memoryCompressedMessages  = newMemoryCompressed,
                            taskState                 = newTaskState ?: state.taskState
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
        val oldStrategy    = _internalState.value.activeStrategy
        val newStrategy    = settingsData.strategy
        val wasPlanningMode = _internalState.value.isPlanningMode
        val nowPlanningMode = settingsData.isPlanningMode

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
            it.copy(
                settingsData    = settingsData,
                isSettingsOpen  = false,
                activeStrategy  = newStrategy,
                isPlanningMode  = nowPlanningMode,
                taskValidationError = if (!nowPlanningMode) null else it.taskValidationError
            )
        }

        when {
            // Planning mode только что включён — загрузить состояние задачи
            !wasPlanningMode && nowPlanningMode -> {
                viewModelScope.launch {
                    val savedTaskState = taskStateMachineAgent?.getTaskState()
                    _internalState.update { it.copy(taskState = savedTaskState) }
                }
            }
            // Стратегия контекста изменилась — применить смену
            newStrategy != oldStrategy && strategyFactory != null -> {
                viewModelScope.launch { applyStrategyChange(newStrategy) }
                return
            }
        }

        viewModelScope.launch { saveHistory() }
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
                lastMessageDuration = null,
                workingMemory = emptyList(),
                longTermMemory = emptyList(),
                memoryCompressedMessages = emptyList(),
                taskValidationError = null
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

            ContextStrategyType.LAYERED_MEMORY -> {
                val savedWorking    = layeredMemoryStrategy?.getWorkingMemory() ?: emptyList()
                val savedLongTerm   = layeredMemoryStrategy?.getLongTermMemory() ?: emptyList()
                val savedCompressed = layeredMemoryStrategy?.getCompressedMessages() ?: emptyList()
                _internalState.update {
                    it.copy(
                        workingMemory            = savedWorking,
                        longTermMemory           = savedLongTerm,
                        memoryCompressedMessages = savedCompressed
                    )
                }
            }

            ContextStrategyType.SLIDING_WINDOW -> { /* ничего специфичного */ }
        }

        saveHistory()
    }

    // ==================== Clear ====================

    private fun clearSession() {
        viewModelScope.launch {
            activeAgent.clearHistory()
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

            // При сбросе LayeredMemory: рабочая и compressed очищаются,
            // долговременная — остаётся (clearSession в MemoryStorage)
            val longTermAfterClear = layeredMemoryStrategy?.getLongTermMemory() ?: emptyList()

            // Planning mode: история чата сбрасывается, задача НЕ сбрасывается
            // (пауза — пользователь должен продолжить задачу)
            val currentTaskState = if (_internalState.value.isPlanningMode)
                taskStateMachineAgent?.getTaskState()
            else null

            _internalState.update {
                it.copy(
                    currentSessionId         = currentSessionId,
                    streamingMessage         = null,
                    lastMessageStats         = null,
                    lastMessageDuration      = null,
                    error                    = null,
                    sessionStats             = SessionTokenStats(),
                    summaries                = emptyList(),
                    facts                    = emptyList(),
                    factsCompressedMessages  = emptyList(),
                    branches                 = newBranches,
                    activeBranchId           = newActiveBranchId,
                    isBranchLimitReached     = false,
                    workingMemory            = emptyList(),
                    longTermMemory           = longTermAfterClear,  // persist!
                    memoryCompressedMessages = emptyList(),
                    taskState                = currentTaskState  // persist задача при паузе!
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
                val updatedFacts = factsStrategy?.refreshFacts(agent.conversationHistory)
                    ?: emptyList()
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

    // ==================== Layered Memory ====================

    /**
     * Обновляет WORKING-память вручную (кнопка 💼 в UI).
     * Использует текущую active-историю агента.
     */
    private fun refreshWorkingMemory() {
        if (_internalState.value.isRefreshingWorkingMemory) return
        _internalState.update { it.copy(isRefreshingWorkingMemory = true, error = null) }
        viewModelScope.launch {
            try {
                val updated = layeredMemoryStrategy?.refreshWorkingMemory(agent.conversationHistory)
                    ?: emptyList()
                _internalState.update {
                    it.copy(workingMemory = updated, isRefreshingWorkingMemory = false)
                }
                saveHistory()
            } catch (e: Exception) {
                _internalState.update {
                    it.copy(
                        isRefreshingWorkingMemory = false,
                        error = e.message ?: "Failed to refresh working memory"
                    )
                }
            }
        }
    }

    /**
     * Обновляет LONG_TERM-память вручную (кнопка 🧠 в UI).
     * Использует текущую active-историю агента.
     * Долговременная память обновляется **только** по явному запросу.
     */
    private fun refreshLongTermMemory() {
        if (_internalState.value.isRefreshingLongTermMemory) return
        _internalState.update { it.copy(isRefreshingLongTermMemory = true, error = null) }
        viewModelScope.launch {
            try {
                val updated = layeredMemoryStrategy?.refreshLongTermMemory(agent.conversationHistory)
                    ?: emptyList()
                _internalState.update {
                    it.copy(longTermMemory = updated, isRefreshingLongTermMemory = false)
                }
                saveHistory()
            } catch (e: Exception) {
                _internalState.update {
                    it.copy(
                        isRefreshingLongTermMemory = false,
                        error = e.message ?: "Failed to refresh long-term memory"
                    )
                }
            }
        }
    }

    /**
     * Полная очистка всей памяти, включая долговременную.
     * Вызывается только по явному запросу пользователя.
     */
    private fun clearAllMemory() {
        viewModelScope.launch {
            try {
                layeredMemoryStrategy?.clearAllMemory()
                _internalState.update {
                    it.copy(
                        workingMemory            = emptyList(),
                        longTermMemory           = emptyList(),
                        memoryCompressedMessages = emptyList()
                    )
                }
            } catch (e: Exception) {
                _internalState.update {
                    it.copy(error = e.message ?: "Failed to clear memory")
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

    // ==================== Task State Machine ====================

    /**
     * Запускает новую задачу с инвариантами по фазам.
     *
     * @param phaseInvariants список пар (phase, rules) из UI-ввода пользователя
     */
    private fun startTask(phaseInvariants: List<PhaseInvariants>) {
        val tsm = taskStateMachineAgent ?: return
        _internalState.update { it.copy(isStartTaskDialogOpen = false, isLoading = true) }
        viewModelScope.launch {
            try {
                val newState = tsm.startTask(phaseInvariants)
                _internalState.update {
                    it.copy(taskState = newState, isLoading = false, taskValidationError = null)
                }
            } catch (e: Exception) {
                _internalState.update {
                    it.copy(isLoading = false, error = e.message ?: "Failed to start task")
                }
            }
        }
    }

    /**
     * Ручной переход к следующей фазе с LLM-валидацией готовности.
     * Вызывается из [TaskStateBubble] (кнопка «→ Next phase»).
     */
    private fun advancePhase() {
        val tsm = taskStateMachineAgent ?: return
        if (_internalState.value.isAdvancingPhase) return
        _internalState.update { it.copy(isAdvancingPhase = true, taskValidationError = null) }
        viewModelScope.launch {
            try {
                when (val result = tsm.advancePhase()) {
                    is AdvancePhaseResult.Advanced -> {
                        _internalState.update {
                            it.copy(taskState = result.newState, isAdvancingPhase = false)
                        }
                    }
                    is AdvancePhaseResult.NotReady -> {
                        val reason = result.reasons.joinToString("; ")
                        _internalState.update {
                            it.copy(
                                isAdvancingPhase = false,
                                taskValidationError = "Not ready to advance: $reason"
                            )
                        }
                    }
                    AdvancePhaseResult.NoActiveTask -> {
                        _internalState.update {
                            it.copy(isAdvancingPhase = false, taskValidationError = "No active task")
                        }
                    }
                    AdvancePhaseResult.AlreadyDone -> {
                        _internalState.update {
                            it.copy(isAdvancingPhase = false, taskValidationError = "Task is already done")
                        }
                    }
                }
            } catch (e: Exception) {
                _internalState.update {
                    it.copy(isAdvancingPhase = false, error = e.message ?: "Failed to advance phase")
                }
            }
        }
    }

    /**
     * Сбрасывает текущую задачу (архивирует) и возвращает в исходное состояние.
     * Вызывается из [TaskStateBubble] (кнопка «✕ Reset»).
     */
    private fun resetTask() {
        val tsm = taskStateMachineAgent ?: return
        viewModelScope.launch {
            try {
                val newState = tsm.resetTask()
                _internalState.update {
                    it.copy(
                        taskState = newState.takeIf { s -> s.isActive },
                        taskValidationError = null
                    )
                }
            } catch (e: Exception) {
                _internalState.update {
                    it.copy(error = e.message ?: "Failed to reset task")
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
            // Planning mode: загружаем персистированное состояние задачи
            if (_internalState.value.isPlanningMode) {
                val savedTaskState = taskStateMachineAgent?.getTaskState()
                _internalState.update { it.copy(taskState = savedTaskState) }
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

            // StickyFacts: загружаем через capability accessor
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

            // LayeredMemory: загружаем все три слоя через capability accessor.
            val savedWorking    = layeredMemoryStrategy?.getWorkingMemory() ?: emptyList()
            val savedLongTerm   = layeredMemoryStrategy?.getLongTermMemory() ?: emptyList()
            val savedMemCompr   = layeredMemoryStrategy?.getCompressedMessages() ?: emptyList()
            if (savedWorking.isNotEmpty() || savedLongTerm.isNotEmpty() || savedMemCompr.isNotEmpty()) {
                _internalState.update {
                    it.copy(
                        workingMemory            = savedWorking,
                        longTermMemory           = savedLongTerm,
                        memoryCompressedMessages = savedMemCompr
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

            // Planning mode: загружаем персистированное состояние задачи
            if (_internalState.value.isPlanningMode) {
                val savedTaskState = taskStateMachineAgent?.getTaskState()
                if (savedTaskState != null) {
                    _internalState.update { it.copy(taskState = savedTaskState) }
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

        // Planning mode: TaskStateMachineAgent сохраняет своё состояние автоматически
        val historyAgent = if (_internalState.value.isPlanningMode && taskStateMachineAgent != null)
            taskStateMachineAgent else agent

        val history = historyAgent.conversationHistory
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
    // Layered Memory
    data object RefreshWorkingMemory  : ChatIntent()
    data object RefreshLongTermMemory : ChatIntent()
    data object ClearAllMemory        : ChatIntent()
    // Task State Machine actions
    data object OpenStartTaskDialog   : ChatIntent()
    data object CloseStartTaskDialog  : ChatIntent()
    /** Запустить задачу с инвариантами по фазам */
    data class StartTask(val phaseInvariants: List<PhaseInvariants>) : ChatIntent()
    /** Ручной переход к следующей фазе (с LLM-валидацией готовности) */
    data object AdvancePhase          : ChatIntent()
    /** Сбросить текущую задачу (архивировать) */
    data object ResetTask             : ChatIntent()
    /** Сбросить ошибку валидации задачи */
    data object ClearTaskError        : ChatIntent()
}
