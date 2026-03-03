package ru.koalexse.aichallenge.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.koalexse.aichallenge.data.persistence.profile.JsonProfileStorage
import ru.koalexse.aichallenge.data.persistence.profile.Profile

// ==================== State ====================

data class ProfileListState(
    val profiles: List<Profile> = emptyList(),
    val selectedProfileId: String = Profile.DEFAULT_PROFILE_ID,
    val isLoading: Boolean = false,
    val error: String? = null,
    /** id профиля, для которого ожидает подтверждения удаление */
    val pendingDeleteId: String? = null
)

// ==================== Intents ====================

sealed class ProfileListIntent {
    data object Load : ProfileListIntent()
    data class SelectProfile(val profileId: String) : ProfileListIntent()
    data class RequestDelete(val profileId: String) : ProfileListIntent()
    data object ConfirmDelete : ProfileListIntent()
    data object CancelDelete : ProfileListIntent()
    data class NavigateToEdit(val profileId: String) : ProfileListIntent()
    data object CreateNew : ProfileListIntent()
}



// ==================== ViewModels ====================

class ProfileListViewModel(
    private val storage: JsonProfileStorage
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileListState(isLoading = true))
    val state: StateFlow<ProfileListState> = _state.asStateFlow()

    init {
        handleIntent(ProfileListIntent.Load)
    }

    fun handleIntent(intent: ProfileListIntent) {
        when (intent) {
            ProfileListIntent.Load -> load()

            is ProfileListIntent.SelectProfile -> {
                viewModelScope.launch {
                    storage.setSelectedId(intent.profileId)
                    _state.update { it.copy(selectedProfileId = intent.profileId) }
                }
            }

            is ProfileListIntent.RequestDelete -> {
                _state.update { it.copy(pendingDeleteId = intent.profileId) }
            }

            ProfileListIntent.ConfirmDelete -> {
                val id = _state.value.pendingDeleteId ?: return
                viewModelScope.launch {
                    storage.delete(id)
                    val updated = storage.getAll()
                    val selectedId = storage.getSelectedId()
                    _state.update {
                        it.copy(
                            profiles = updated,
                            selectedProfileId = selectedId,
                            pendingDeleteId = null
                        )
                    }
                }
            }

            ProfileListIntent.CancelDelete -> {
                _state.update { it.copy(pendingDeleteId = null) }
            }

            // Навигационные интенты — обрабатываются снаружи во ViewModel через callback
            is ProfileListIntent.NavigateToEdit -> { /* handled by screen */ }
            ProfileListIntent.CreateNew -> { /* handled by screen */ }
        }
    }

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val profiles = storage.getAll()
                val selectedId = storage.getSelectedId()
                _state.update {
                    it.copy(
                        profiles = profiles,
                        selectedProfileId = selectedId,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(isLoading = false, error = e.message ?: "Failed to load profiles")
                }
            }
        }
    }
}


