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
import ru.koalexse.aichallenge.agent.isUser
import ru.koalexse.aichallenge.domain.Message
import ru.koalexse.aichallenge.domain.TokenStats
import ru.koalexse.aichallenge.ui.state.ChatUiState
import ru.koalexse.aichallenge.ui.state.SettingsData
import ru.koalexse.aichallenge.ui.state.toSettingsData

/**
 * ViewModel для чата, работающая напрямую с Agent
 * 
 * История сообщений хранится в агенте.
 */
class AgentChatViewModel(
    private val agent: Agent,
    private val availableModels: List<String>,
) : ViewModel() {

    /**
     * Состояние для стриминга сообщения
     */
    private data class StreamingMessage(
        val text: String = "",
        val tokenStats: TokenStats? = null,
        val responseDurationMs: Long? = null
    )

    /**
     * Внутреннее состояние ViewModel
     */
    private data class InternalState(
        val currentInput: String = "",
        val isLoading: Boolean = false,
        val isSettingsOpen: Boolean = false,
        val error: String? = null,
        val settingsData: SettingsData,
        val streamingMessage: StreamingMessage? = null,
        // Просто храним последнюю статистику для текущего сообщения
        val lastMessageStats: TokenStats? = null,
        val lastMessageDuration: Long? = null
    )

    private val _internalState = MutableStateFlow(
        InternalState(
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
    }

    private fun buildUiState(): ChatUiState {
        val internal = _internalState.value

        // Получаем историю из агента
        val historyMessages = agent.conversationHistory.mapIndexed { index, agentMessage ->
            // Статистику привязываем к индексу сообщения
            val isLastAssistant =
                !agentMessage.isUser && index == agent.conversationHistory.lastIndex
            val tokenStats = if (isLastAssistant) internal.lastMessageStats else null
            val duration = if (isLastAssistant) internal.lastMessageDuration else null

            agentMessage.toUiMessage(
                id = "msg_$index",
                tokenStats = tokenStats,
                responseDurationMs = duration
            )
        }

        // Добавляем стримящееся сообщение если есть
        val allMessages = if (internal.streamingMessage != null) {
            historyMessages + internal.streamingMessage.toUiMessage()
        } else {
            historyMessages
        }

        return ChatUiState(
            messages = allMessages,
            availableModels = availableModels,
            settingsData = internal.settingsData,
            currentInput = internal.currentInput,
            isLoading = internal.isLoading,
            isSettingsOpen = internal.isSettingsOpen,
            error = internal.error
        )
    }

    fun handleIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.UpdateInput -> {
                _internalState.update { it.copy(currentInput = intent.text) }
            }

            is ChatIntent.SendMessage -> {
                sendMessage(intent.text)
            }

            is ChatIntent.ClearError -> {
                _internalState.update { it.copy(error = null) }
            }

            ChatIntent.OpenSettings -> {
                _internalState.update { it.copy(isSettingsOpen = !it.isSettingsOpen) }
            }

            is ChatIntent.SaveSettings -> {
                handleSettingsUpdate(intent.settingsData)
            }

            ChatIntent.ClearSession -> {
                clearSession()
            }
        }
    }

    private fun sendMessage(text: String) {
        _internalState.update {
            it.copy(
                currentInput = "",
                error = null,
                isLoading = true,
            )
        }

        viewModelScope.launch {
            handleAgentStream(text)
        }
    }

    private suspend fun handleAgentStream(userText: String) {
        agent.send(userText).also {
            _internalState.update {
                it.copy(
                    streamingMessage = StreamingMessage(), // Пустое сообщение для стриминга
                    lastMessageStats = null, // Сбрасываем статистику прошлого сообщения
                    lastMessageDuration = null,
                )
            }
        }.onEach { event ->
            when (event) {
                is AgentStreamEvent.ContentDelta -> {
                    // Просто добавляем текст к стримящемуся сообщению
                    _internalState.update { state ->
                        state.copy(
                            streamingMessage = state.streamingMessage?.copy(
                                text = state.streamingMessage.text + event.text
                            )
                        )
                    }
                }

                is AgentStreamEvent.Completed -> {
                    // Сохраняем статистику в streaming message
                    _internalState.update { state ->
                        state.copy(
                            streamingMessage = state.streamingMessage?.copy(
                                tokenStats = event.tokenStats,
                                responseDurationMs = event.durationMs
                            )
                        )
                    }
                }

                is AgentStreamEvent.Error -> {
                    handleError(event.exception)
                }
            }
        }
            .onCompletion { error ->
                if (error == null) {
                    // Завершаем стриминг и сохраняем статистику
                    val streamingMsg = _internalState.value.streamingMessage
                    _internalState.update { state ->
                        state.copy(
                            isLoading = false,
                            streamingMessage = null,
                            lastMessageStats = streamingMsg?.tokenStats,
                            lastMessageDuration = streamingMsg?.responseDurationMs
                        )
                    }
                }
            }
            .catch { error ->
                handleError(error)
            }
            .collect()
    }

    private fun handleError(error: Throwable) {
        _internalState.update {
            it.copy(
                isLoading = false,
                streamingMessage = null,
                error = error.message ?: "Unknown error"
            )
        }
    }

    private fun handleSettingsUpdate(settingsData: SettingsData) {
        _internalState.update {
            it.copy(settingsData = settingsData, isSettingsOpen = false)
        }

        val temperature = runCatching { settingsData.temperature?.toFloat() }.getOrNull()
        val tokens = runCatching { settingsData.tokens?.toLong() }.getOrNull()

        agent.updateConfig(
            agent.config.copy(
                defaultModel = settingsData.model,
                defaultTemperature = temperature,
                defaultMaxTokens = tokens
            )
        )
    }

    private fun clearSession() {
        agent.clearHistory()
        _internalState.update {
            it.copy(
                streamingMessage = null,
                lastMessageStats = null,
                lastMessageDuration = null,
                error = null
            )
        }
    }

    // ==================== Конвертеры ====================

    private fun AgentMessage.toUiMessage(
        id: String,
        tokenStats: TokenStats? = null,
        responseDurationMs: Long? = null
    ): Message = Message(
        id = id,
        isUser = role == Role.USER,
        text = content,
        isLoading = false,
        tokenStats = tokenStats,
        responseDurationMs = responseDurationMs
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
