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

sealed class ProfileEditIntent {
    data class Load(val profileId: String) : ProfileEditIntent()
    data class UpdateName(val name: String) : ProfileEditIntent()
    data class UpdateRawText(val rawText: String) : ProfileEditIntent()
    data object Save : ProfileEditIntent()
    data object ClearError : ProfileEditIntent()
    data object ClearSaved : ProfileEditIntent()
}
data class ProfileEditState(
    val profile: Profile = Profile(),
    val isLoading: Boolean = false,
    val isSaved: Boolean = false,
    val error: String? = null
)

class ProfileEditViewModel(
    private val storage: JsonProfileStorage
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileEditState())
    val state: StateFlow<ProfileEditState> = _state.asStateFlow()

    fun handleIntent(intent: ProfileEditIntent) {
        when (intent) {
            is ProfileEditIntent.Load -> load(intent.profileId)

            is ProfileEditIntent.UpdateName -> {
                _state.update { it.copy(profile = it.profile.copy(name = intent.name)) }
            }

            is ProfileEditIntent.UpdateRawText -> {
                _state.update { it.copy(profile = it.profile.copy(rawText = intent.rawText)) }
            }

            ProfileEditIntent.Save -> save()

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
                _state.update { it.copy(profile = profile, isLoading = false) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(isLoading = false, error = e.message ?: "Failed to load profile")
                }
            }
        }
    }

    private fun save() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                storage.save(_state.value.profile)
                _state.update { it.copy(isLoading = false, isSaved = true) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(isLoading = false, error = e.message ?: "Failed to save profile")
                }
            }
        }
    }
}