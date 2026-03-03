package ru.koalexse.aichallenge.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Главный экран — чат */
@Serializable
data object ChatRoute : NavKey

/** Список профилей */
@Serializable
data object ProfileListRoute : NavKey

/** Редактирование профиля */
@Serializable
data class ProfileEditRoute(val profileId: String) : NavKey
