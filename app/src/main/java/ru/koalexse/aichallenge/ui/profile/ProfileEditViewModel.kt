package ru.koalexse.aichallenge.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.koalexse.aichallenge.agent.AgentMessage
import ru.koalexse.aichallenge.agent.Role
import ru.koalexse.aichallenge.agent.context.summary.SummaryProvider
import ru.koalexse.aichallenge.data.persistence.profile.JsonProfileStorage
import ru.koalexse.aichallenge.data.persistence.profile.Profile

sealed class ProfileEditIntent {
    data class Load(val profileId: String) : ProfileEditIntent()
    data class UpdateName(val name: String) : ProfileEditIntent()
    data class UpdateRawText(val rawText: String) : ProfileEditIntent()
    data object Save : ProfileEditIntent()
    data object ExtractFacts : ProfileEditIntent()
    data object ClearError : ProfileEditIntent()
    data object ClearSaved : ProfileEditIntent()
}

data class ProfileEditState(
    val profile: Profile = Profile(),
    val isLoading: Boolean = false,
    val isExtracting: Boolean = false,
    val isSaved: Boolean = false,
    val error: String? = null
)

class ProfileEditViewModel(
    private val storage: JsonProfileStorage,
    private val summaryProvider: SummaryProvider
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileEditState())
    val state: StateFlow<ProfileEditState> = _state.asStateFlow()

    /**
     * rawText.trim(), зафиксированный в момент последнего успешного [persist] или [load].
     * null — профиль ещё не был загружен / сохранён в этой сессии.
     * Используется в [save], чтобы не извлекать факты повторно, если текст не менялся.
     */
    private var lastPersistedRawText: String? = null

    fun handleIntent(intent: ProfileEditIntent) {
        when (intent) {
            is ProfileEditIntent.Load -> load(intent.profileId)

            is ProfileEditIntent.UpdateName -> {
                _state.update { it.copy(profile = it.profile.copy(name = intent.name)) }
            }

            is ProfileEditIntent.UpdateRawText -> {
                _state.update {
                    it.copy(
                        profile = it.profile.copy(rawText = intent.rawText),
                    )
                }
            }

            ProfileEditIntent.Save -> save()

            ProfileEditIntent.ExtractFacts -> extractFacts()

            ProfileEditIntent.ClearError -> {
                _state.update { it.copy(error = null) }
            }

            ProfileEditIntent.ClearSaved -> {
                _state.update { it.copy(isSaved = false) }
            }
        }
    }

    private fun load(profileId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val profile = storage.getById(profileId) ?: Profile()
                lastPersistedRawText = profile.rawText.trim()
                _state.update { it.copy(profile = profile, isLoading = false) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(isLoading = false, error = e.message ?: "Failed to load profile")
                }
            }
        }
    }

    /**
     * Извлекает факты из [Profile.rawText] через [SummaryProvider] с facts-промптом,
     * перезаписывает [Profile.facts] и затем сохраняет профиль в хранилище.
     *
     * Если rawText пустой — пропускаем извлечение и сразу сохраняем.
     * Если rawText не изменился с момента последнего успешного сохранения —
     * пропускаем дорогой LLM-вызов и сразу сохраняем.
     */
    private fun save() {
        val rawText = _state.value.profile.rawText.trim()
        if (rawText.isBlank()) {
            persist()
            return
        }
        if (rawText == lastPersistedRawText) {
            persist()
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isExtracting = true, isLoading = true, error = null) }
            try {
                val facts = extractFactsFromText(rawText)
                _state.update {
                    it.copy(
                        profile = it.profile.copy(facts = facts),
                        isExtracting = false
                    )
                }
            } catch (e: Exception) {
                // Не блокируем сохранение при ошибке извлечения — сохраняем с прежними фактами
                _state.update { it.copy(isExtracting = false) }
            }
            persist()
        }
    }

    /**
     * Явное извлечение фактов по кнопке — обновляет state без сохранения.
     */
    private fun extractFacts() {
        val rawText = _state.value.profile.rawText.trim()
        if (rawText.isBlank()) return

        viewModelScope.launch {
            _state.update { it.copy(isExtracting = true, error = null) }
            try {
                val facts = extractFactsFromText(rawText)
                _state.update {
                    it.copy(
                        profile = it.profile.copy(facts = facts),
                        isExtracting = false,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isExtracting = false,
                        error = e.message ?: "Failed to extract facts"
                    )
                }
            }
        }
    }

    /**
     * Вызывает [SummaryProvider.summarize] с одним USER-сообщением,
     * разбивает ответ по строкам и чистит маркеры списка.
     */
    private suspend fun extractFactsFromText(rawText: String): List<String> {
        val messages = listOf(AgentMessage(role = Role.USER, content = rawText))
        val factsText = summaryProvider.summarize(messages)
        return factsText
            .lines()
            .map { it.trimStart('-', '*', '•', ' ').trim() }
            .filter { it.isNotBlank() }
    }

    /** Сохраняет текущий [_state].profile в хранилище и выставляет isSaved. */
    private fun persist() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                storage.save(_state.value.profile)
                lastPersistedRawText = _state.value.profile.rawText.trim()
                _state.update { it.copy(isLoading = false, isSaved = true) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(isLoading = false, error = e.message ?: "Failed to save profile")
                }
            }
        }
    }
}
