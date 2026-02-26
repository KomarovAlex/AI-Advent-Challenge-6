package ru.koalexse.aichallenge.ui.state

import ru.koalexse.aichallenge.agent.AgentConfig
import ru.koalexse.aichallenge.domain.Message
import ru.koalexse.aichallenge.domain.SessionTokenStats

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val availableModels: List<String> = emptyList(),
    val settingsData: SettingsData,
    val currentInput: String = "",
    val isLoading: Boolean = false,
    val isSettingsOpen: Boolean = false,
    val error: String? = null,
    val sessionStats: SessionTokenStats? = null,
    /**
     * Количество сообщений, сжатых в summaries
     * Показывает, сколько сообщений было заменено на краткое описание
     */
    val compressedMessageCount: Int = 0
)

data class SettingsData(
    val model: String,
    val temperature: String? = null,
    val tokens: String? = null,
)

fun AgentConfig.toSettingsData() = SettingsData(
    model = defaultModel,
    temperature = defaultTemperature?.toString(),
    tokens = defaultMaxTokens?.toString()
)

fun SettingsData.isEmpty() =
    model.isEmpty() && temperature.isNullOrEmpty() && tokens.isNullOrEmpty()
