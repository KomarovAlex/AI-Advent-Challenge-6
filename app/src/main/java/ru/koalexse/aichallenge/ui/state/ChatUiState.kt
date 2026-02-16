package ru.koalexse.aichallenge.ui.state

import ru.koalexse.aichallenge.domain.Message

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val currentInput: String = "",
    val isLoading: Boolean = false,
    val error: String? = null
)