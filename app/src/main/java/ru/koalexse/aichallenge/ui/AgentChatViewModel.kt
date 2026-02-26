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
import ru.koalexse.aichallenge.agent.Role
import ru.koalexse.aichallenge.agent.context.summary.ConversationSummary
import ru.koalexse.aichallenge.agent.context.summary.SummaryStorage
import ru.koalexse.aichallenge.agent.isUser
import ru.koalexse.aichallenge.data.persistence.ChatHistoryMapper.toMessages
import ru.koalexse.aichallenge.data.persistence.ChatHistoryMapper.toSession
import ru.koalexse.aichallenge.data.persistence.ChatHistoryMapper.toSessionTokenStats
import ru.koalexse.aichallenge.data.persistence.ChatHistoryMapper.toSummaries
import ru.koalexse.aichallenge.data.persistence.ChatHistoryRepository
import ru.koalexse.aichallenge.domain.Message
import ru.koalexse.aichallenge.domain.SessionTokenStats
import ru.koalexse.aichallenge.domain.TokenStats
import ru.koalexse.aichallenge.ui.state.ChatUiState
import ru.koalexse.aichallenge.ui.state.SettingsData
import ru.koalexse.aichallenge.ui.state.toSettingsData
import java.util.UUID

class AgentChatViewModel(
    private val agent: Agent,
    private val availableModels: List<String>,
    private val chatHistoryRepository: ChatHistoryRepository? = null,
    private val summaryStorage: SummaryStorage? = null
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
        // Список сжатых summaries — для отображения оригинальных сообщений в UI
        val summaries: List<ConversationSummary> = emptyList()
    )

    private var currentSessionId: String = UUID.randomUUID().toString()
    private var sessionCreatedAt: Long = System.currentTimeMillis()

    private val _internalState = MutableStateFlow(
        InternalState(
            currentSessionId = currentSessionId,
            settingsData = agent.config.toSettingsData()
        )
    )

    private val _state = MutableStateFlow(buildUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _internalState.collect {
                _state.value = buildUiState()
            }
        }
        viewModelScope.launch {
            loadSavedHistory()
        }
    }

    private fun buildUiState(): ChatUiState {
        val internal = _internalState.value

        // Сжатые сообщения из summaries (только для отображения, не отправляются в LLM)
        val compressedMessages = internal.summaries.flatMapIndexed { summaryIndex, summary ->
            summary.originalMessages.mapIndexed { msgIndex, agentMessage ->
                agentMessage.toUiMessage(
                    id = "compressed_${summaryIndex}_${msgIndex}",
                    isCompressed = true
                )
            }
        }

        // Активные сообщения из истории агента
        val historyMessages = agent.conversationHistory.mapIndexed { index, agentMessage ->
            val isLastAssistant = !agentMessage.isUser && index == agent.conversationHistory.lastIndex
            agentMessage.toUiMessage(
                id = "msg_$index",
                tokenStats = if (isLastAssistant) internal.lastMessageStats else null,
                responseDurationMs = if (isLastAssistant) internal.lastMessageDuration else null
            )
        }

        // Стримящееся сообщение
        val streamingMessages = if (internal.streamingMessage != null) {
            listOf(internal.streamingMessage.toUiMessage())
        } else {
            emptyList()
        }

        val allMessages = compressedMessages + historyMessages + streamingMessages

        return ChatUiState(
            messages = allMessages,
            availableModels = availableModels,
            settingsData = internal.settingsData,
            currentInput = internal.currentInput,
            isLoading = internal.isLoading || internal.isHistoryLoading,
            isSettingsOpen = internal.isSettingsOpen,
            error = internal.error,
            sessionStats = internal.sessionStats.takeIf { it.messageCount > 0 },
            compressedMessageCount = compressedMessages.size
        )
    }

    fun handleIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.UpdateInput ->
                _internalState.update { it.copy(currentInput = intent.text) }

            is ChatIntent.SendMessage ->
                sendMessage(intent.text)

            is ChatIntent.ClearError ->
                _internalState.update { it.copy(error = null) }

            ChatIntent.OpenSettings ->
                _internalState.update { it.copy(isSettingsOpen = !it.isSettingsOpen) }

            is ChatIntent.SaveSettings ->
                handleSettingsUpdate(intent.settingsData)

            ChatIntent.ClearSession ->
                clearSession()
        }
    }

    private fun sendMessage(text: String) {
        _internalState.update { it.copy(currentInput = "", error = null, isLoading = true) }
        viewModelScope.launch { handleAgentStream(text) }
    }

    private suspend fun handleAgentStream(userText: String) {
        agent.send(userText).also {
            _internalState.update {
                it.copy(
                    streamingMessage = StreamingMessage(),
                    lastMessageStats = null,
                    lastMessageDuration = null,
                )
            }
        }.onEach { event ->
            when (event) {
                is AgentStreamEvent.ContentDelta -> {
                    _internalState.update { state ->
                        state.copy(
                            streamingMessage = state.streamingMessage?.copy(
                                text = state.streamingMessage.text + event.text
                            )
                        )
                    }
                }

                is AgentStreamEvent.Completed -> {
                    val newSummaries = loadCurrentSummaries()
                    _internalState.update { state ->
                        state.copy(
                            streamingMessage = state.streamingMessage?.copy(
                                tokenStats = event.tokenStats,
                                responseDurationMs = event.durationMs
                            ),
                            sessionStats = state.sessionStats.add(event.tokenStats),
                            summaries = newSummaries
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

    private fun handleSettingsUpdate(settingsData: SettingsData) {
        _internalState.update { it.copy(settingsData = settingsData, isSettingsOpen = false) }

        val temperature = runCatching { settingsData.temperature?.toFloat() }.getOrNull()
        val tokens = runCatching { settingsData.tokens?.toLong() }.getOrNull()

        agent.updateConfig(
            agent.config.copy(
                defaultModel = settingsData.model,
                defaultTemperature = temperature,
                defaultMaxTokens = tokens
            )
        )

        viewModelScope.launch { saveHistory() }
    }

    private fun clearSession() {
        viewModelScope.launch {
            agent.clearHistory()

            currentSessionId = UUID.randomUUID().toString()
            sessionCreatedAt = System.currentTimeMillis()

            _internalState.update {
                it.copy(
                    currentSessionId = currentSessionId,
                    streamingMessage = null,
                    lastMessageStats = null,
                    lastMessageDuration = null,
                    error = null,
                    sessionStats = SessionTokenStats(),
                    summaries = emptyList()
                )
            }
            chatHistoryRepository?.clearAll()
        }
    }

    // ==================== Вспомогательные методы ====================

    /**
     * Загружает актуальный список summaries из storage
     */
    private suspend fun loadCurrentSummaries(): List<ConversationSummary> {
        return summaryStorage?.getSummaries() ?: emptyList()
    }

    // ==================== Persistence ====================

    private suspend fun loadSavedHistory() {
        if (chatHistoryRepository == null) {
            _internalState.update { it.copy(isHistoryLoading = false) }
            return
        }

        try {
            val session = chatHistoryRepository.loadActiveSession()

            if (session != null && session.messages.isNotEmpty()) {
                currentSessionId = session.id
                sessionCreatedAt = session.createdAt

                val savedSummaries = session.toSummaries()
                if (savedSummaries.isNotEmpty() && summaryStorage != null) {
                    summaryStorage.loadSummaries(savedSummaries)
                }

                val messages = session.toMessages()
                messages.forEach { message -> agent.addToHistory(message) }

                session.model?.let { model ->
                    if (model in availableModels) {
                        agent.updateConfig(agent.config.copy(defaultModel = model))
                        _internalState.update { state ->
                            state.copy(settingsData = state.settingsData.copy(model = model))
                        }
                    }
                }

                val savedStats = session.toSessionTokenStats()

                _internalState.update { state ->
                    state.copy(
                        sessionStats = savedStats ?: SessionTokenStats(),
                        summaries = savedSummaries
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

        val history = agent.conversationHistory
        if (history.isEmpty()) return

        try {
            val sessionStats = _internalState.value.sessionStats
            val summaries = summaryStorage?.getSummaries() ?: emptyList()

            val session = history.toSession(
                sessionId = currentSessionId,
                model = agent.config.defaultModel,
                createdAt = sessionCreatedAt,
                sessionStats = sessionStats.takeIf { it.messageCount > 0 },
                summaries = summaries
            )

            chatHistoryRepository.saveSession(session)
            chatHistoryRepository.setActiveSession(currentSessionId)
        } catch (e: Exception) {
            // Логируем ошибку
            println(e)
        }
    }

    // ==================== Конвертеры ====================

    private fun AgentMessage.toUiMessage(
        id: String,
        tokenStats: TokenStats? = null,
        responseDurationMs: Long? = null,
        isCompressed: Boolean = false
    ): Message = Message(
        id = id,
        isUser = role == Role.USER,
        text = content,
        isLoading = false,
        tokenStats = tokenStats,
        responseDurationMs = responseDurationMs,
        isCompressed = isCompressed
    )

    private fun StreamingMessage.toUiMessage(): Message = Message(
        id = "streaming_msg",
        isUser = false,
        text = text,
        isLoading = true,
        tokenStats = tokenStats,
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
}
