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
import ru.koalexse.aichallenge.data.ChatRepository
import ru.koalexse.aichallenge.domain.Message
import ru.koalexse.aichallenge.domain.StatsStreamResult
import ru.koalexse.aichallenge.domain.TokenStats
import ru.koalexse.aichallenge.ui.state.ChatUiState

class ChatViewModel(
    private val repository: ChatRepository,
    private val availableModels: List<String>,
) : ViewModel() {

    // Единственный источник правды для UI
    private val _state = MutableStateFlow(
        ChatUiState(
            availableModels = availableModels,
            settingsData = SettingsData(availableModels.first())
        )
    )
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    // Обработка намерений пользователя
    fun handleIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.UpdateInput -> {
                _state.value = _state.value.copy(currentInput = intent.text)
            }

            is ChatIntent.SendMessage -> {
                sendMessage(intent.text)
            }

            is ChatIntent.ClearError -> {
                _state.value = _state.value.copy(error = null)
            }

            ChatIntent.OpenSettings -> {
                _state.update {
                    it.copy(isSettingsOpen = !it.isSettingsOpen)
                }
            }

            is ChatIntent.SaveSettings -> {
                _state.update {
                    it.copy(settingsData = intent.settingsData, isSettingsOpen = false)
                }
            }

            ChatIntent.ClearSession -> {
                _state.update { it.copy(messages = emptyList()) }
            }
        }
    }

    private fun sendMessage(text: String) {
        val userMessage = createUserMessage(text)
        updateStateWithUserMessage(userMessage)

        viewModelScope.launch {
            val assistantMessageId = generateMessageId()
            showAssistantLoading(assistantMessageId)

            handleAssistantStream(assistantMessageId)
        }
    }

    private fun createUserMessage(text: String) = Message(
        id = generateMessageId("user"),
        isUser = true,
        text = text
    )

    private fun generateMessageId(prefix: String = "msg") =
        "${System.currentTimeMillis()}_$prefix"

    private fun updateStateWithUserMessage(userMessage: Message) {
        _state.update { currentState ->
            currentState.copy(
                messages = currentState.messages + userMessage,
                currentInput = "",
                error = null
            )
        }
    }

    private suspend fun handleAssistantStream(messageId: String) {
        val temperature =
            runCatching { _state.value.settingsData.temperature?.toFloat() }.getOrNull()
        val tokens = runCatching { _state.value.settingsData.tokens?.toLong() }.getOrNull()
        repository.sendMessage(
            messages = _state.value.messages,
            temperature = temperature,
            tokens = tokens,
            model = state.value.settingsData.model
        )
            .onEach { result ->
                when (result) {
                    is StatsStreamResult.Content -> updateAssistantMessage(messageId, result.text)
                    is StatsStreamResult.Stats -> updateAssistantMessageStats(
                        messageId,
                        result.tokenStats,
                        result.durationMs
                    )
                }
            }
            .onCompletion { finishAssistantMessage(messageId) }
            .catch { error -> handleAssistantError(messageId, error) }
            .collect()
    }

    private fun showAssistantLoading(messageId: String) {
        _state.update { currentState ->
            currentState.copy(
                messages = currentState.messages + Message(
                    id = messageId,
                    isUser = false,
                    text = "",
                    isLoading = true
                ),
                isLoading = true
            )
        }
    }

    private fun updateAssistantMessage(messageId: String, chunk: String) {
        _state.update { currentState ->
            val updatedMessages = currentState.messages.map { message ->
                if (message.id == messageId) {
                    message.copy(text = message.text + chunk)
                } else {
                    message
                }
            }
            currentState.copy(messages = updatedMessages)
        }
    }

    private fun updateAssistantMessageStats(
        messageId: String,
        tokenStats: TokenStats,
        durationMs: Long
    ) {
        _state.update { currentState ->
            val updatedMessages = currentState.messages.map { message ->
                if (message.id == messageId) {
                    message.copy(tokenStats = tokenStats, responseDurationMs = durationMs)
                } else {
                    message
                }
            }
            currentState.copy(messages = updatedMessages)
        }
    }

    private fun finishAssistantMessage(messageId: String) {
        _state.update { currentState ->
            val updatedMessages = currentState.messages.map { message ->
                if (message.id == messageId) {
                    message.copy(isLoading = false)
                } else {
                    message
                }
            }
            currentState.copy(
                messages = updatedMessages,
                isLoading = false
            )
        }
    }

    private fun handleAssistantError(messageId: String, error: Throwable) {
        _state.update { currentState ->
            currentState.copy(
                messages = currentState.messages.filter { it.id != messageId },
                isLoading = false,
                error = error.message ?: "Unknown error"
            )
        }
    }
}

sealed class ChatIntent {
    data class UpdateInput(val text: String) : ChatIntent()
    data class SendMessage(val text: String) : ChatIntent()
    data object ClearError : ChatIntent()
    data object ClearSession : ChatIntent()
    data object OpenSettings : ChatIntent()
    data class SaveSettings(val settingsData: SettingsData) : ChatIntent()
}
