package ru.koalexse.aichallenge.ui.state

import ru.koalexse.aichallenge.domain.Message
import ru.koalexse.aichallenge.ui.SettingsData

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val settingsData: SettingsData = SettingsData(),
    val currentInput: String = "",
    val isLoading: Boolean = false,
    val isSettingsOpen: Boolean = false,
    val error: String? = null,
)