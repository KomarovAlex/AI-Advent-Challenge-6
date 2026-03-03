package ru.koalexse.aichallenge.data.persistence.profile

import java.util.UUID

/**
 * Профиль пользователя.
 *
 * @param id уникальный идентификатор; у профиля по умолчанию = [DEFAULT_PROFILE_ID]
 * @param name отображаемое название
 * @param rawText свободный текст пользователя (источник для извлечения фактов)
 * @param facts извлечённые факты для формирования системного промпта
 * @param isDefault true для профиля по умолчанию — нельзя удалить и переименовать
 */
data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val rawText: String = "",
    val facts: List<String> = emptyList(),
    val isDefault: Boolean = false
) {
    companion object {
        const val DEFAULT_PROFILE_ID = "default"

        fun createDefault() = Profile(
            id = DEFAULT_PROFILE_ID,
            name = "Default",
            rawText = "",
            facts = emptyList(),
            isDefault = true
        )
    }
}
