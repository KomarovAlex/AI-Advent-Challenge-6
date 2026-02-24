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
import ru.koalexse.aichallenge.data.persistence.ChatHistoryMapper.toMessages
import ru.koalexse.aichallenge.data.persistence.ChatHistoryMapper.toSession
import ru.koalexse.aichallenge.data.persistence.ChatHistoryRepository
import ru.koalexse.aichallenge.domain.Message
import ru.koalexse.aichallenge.domain.TokenStats
import ru.koalexse.aichallenge.ui.state.ChatUiState
import ru.koalexse.aichallenge.ui.state.SettingsData
import ru.koalexse.aichallenge.ui.state.toSettingsData
import java.util.UUID

/**
 * ViewModel для чата, работающая напрямую с Agent
 * 
 * История сообщений хранится в агенте и автоматически сохраняется
 * в репозиторий между запусками приложения.
 * 
 * @param agent агент для работы с LLM
 * @param availableModels список доступных моделей
 * @param chatHistoryRepository репозиторий для сохранения истории (опционально)
 */
class AgentChatViewModel(
    private val agent: Agent,
    private val availableModels: List<String>,
    private val chatHistoryRepository: ChatHistoryRepository? = null
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
        val currentSessionId: String,
        val currentInput: String = "",
        val isLoading: Boolean = false,
        val isSettingsOpen: Boolean = false,
        val error: String? = null,
        val settingsData: SettingsData,
        val streamingMessage: StreamingMessage? = null,
        // Просто храним последнюю статистику для текущего сообщения
        val lastMessageStats: TokenStats? = null,
        val lastMessageDuration: Long? = null,
        // Флаг загрузки истории
        val isHistoryLoading: Boolean = true
    )

    // ID текущей сессии
    private var currentSessionId: String = UUID.randomUUID().toString()

    // Время создания сессии (для корректного сохранения)
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

        // Загружаем сохранённую историю при старте
        viewModelScope.launch {
            loadSavedHistory()
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
            isLoading = internal.isLoading || internal.isHistoryLoading,
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

                    // Сохраняем историю после получения ответа
                    saveHistory()
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

        // Сохраняем историю после изменения настроек (чтобы сохранить модель)
        viewModelScope.launch {
            saveHistory()
        }
    }

    private fun clearSession() {
        agent.clearHistory()
        // Создаём новую сессию
        currentSessionId = UUID.randomUUID().toString()
        sessionCreatedAt = System.currentTimeMillis()

        _internalState.update {
            it.copy(
                currentSessionId = currentSessionId,
                streamingMessage = null,
                lastMessageStats = null,
                lastMessageDuration = null,
                error = null,
            )
        }

        // Удаляем старую сессию из хранилища
        viewModelScope.launch {
            chatHistoryRepository?.clearAll()
        }
    }

    // ==================== Persistence ====================

    /**
     * Загружает сохранённую историю из репозитория
     */
    private suspend fun loadSavedHistory() {
        if (chatHistoryRepository == null) {
            _internalState.update { it.copy(isHistoryLoading = false) }
            return
        }

        try {
            val session = chatHistoryRepository.loadActiveSession()

            if (session != null && session.messages.isNotEmpty()) {
                // Восстанавливаем ID сессии
                currentSessionId = session.id
                sessionCreatedAt = session.createdAt

                // Загружаем сообщения в агента
                val messages = session.toMessages()
                messages.forEach { message ->
                    agent.addToHistory(message)
                }

                // Если в сессии сохранена модель, обновляем настройки
                session.model?.let { model ->
                    if (model in availableModels) {
                        agent.updateConfig(agent.config.copy(defaultModel = model))
                        _internalState.update { state ->
                            state.copy(
                                settingsData = state.settingsData.copy(model = model)
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Логируем ошибку, но не показываем пользователю
            // История просто не загрузится
        } finally {
            _internalState.update { it.copy(isHistoryLoading = false) }
        }
    }

    /**
     * Сохраняет текущую историю в репозиторий
     */
    private suspend fun saveHistory() {
        if (chatHistoryRepository == null) return

        val history = agent.conversationHistory
        if (history.isEmpty()) return

        try {
            val session = history.toSession(
                sessionId = currentSessionId,
                model = agent.config.defaultModel,
                createdAt = sessionCreatedAt
            )

            chatHistoryRepository.saveSession(session)
            chatHistoryRepository.setActiveSession(currentSessionId)
        } catch (e: Exception) {
            // Логируем ошибку, но не показываем пользователю
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
