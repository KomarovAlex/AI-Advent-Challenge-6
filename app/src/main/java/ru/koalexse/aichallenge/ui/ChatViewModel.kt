package ru.koalexse.aichallenge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.koalexse.aichallenge.data.ChatRepository
import ru.koalexse.aichallenge.domain.Message
import ru.koalexse.aichallenge.ui.state.ChatUiState

class ChatViewModel(
    private val repository: ChatRepository
) : ViewModel() {

    // Единственный источник правды для UI
    private val _state = MutableStateFlow(ChatUiState())
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
        }
    }

    private fun sendMessage(text: String) {
        val userMessage = Message(
            isUser = true,
            text = text
        )

        // Добавляем сообщение пользователя и показываем загрузку
        _state.value = _state.value.copy(
            messages = _state.value.messages + userMessage,
            currentInput = "",
            isLoading = true,
            error = null
        )

        // Добавляем плейсхолдер для ответа
        val loadingMessage = Message(
            isUser = false,
            text = "...",
            isLoading = true
        )
        _state.value = _state.value.copy(
            messages = _state.value.messages + loadingMessage
        )

        viewModelScope.launch {
            val result = repository.sendMessage(
                messages = _state.value.messages
            )

            // Убираем loading сообщение
            val messagesWithoutLoading = _state.value.messages.filter { !it.isLoading }

            result.onSuccess { response ->
                // Добавляем ответ ассистента
                _state.value = _state.value.copy(
                    messages = messagesWithoutLoading + Message(
                        isUser = false,
                        text = response
                    ),
                    isLoading = false
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    messages = messagesWithoutLoading,
                    isLoading = false,
                    error = error.message ?: "Unknown error"
                )
            }
        }
    }
}

sealed class ChatIntent {
    data class UpdateInput(val text: String) : ChatIntent()
    data class SendMessage(val text: String) : ChatIntent()
    data object ClearError : ChatIntent()
}