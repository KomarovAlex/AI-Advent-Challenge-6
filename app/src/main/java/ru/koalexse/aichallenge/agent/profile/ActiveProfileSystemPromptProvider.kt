package ru.koalexse.aichallenge.agent.profile

import ru.koalexse.aichallenge.data.persistence.profile.Profile

/**
 * Реализация [ProfileSystemPromptProvider], которая динамически читает активный профиль
 * при каждом запросе через [getActiveProfile].
 *
 * Агент (`SimpleLLMAgent`) не зависит от Android и не знает о `JsonProfileStorage` —
 * конкретный источник данных скрыт за lambda `getActiveProfile`.
 *
 * В `AppModule` lambda пробрасывает `profileStorage`:
 * ```kotlin
 * ActiveProfileSystemPromptProvider {
 *     val id = profileStorage.getSelectedId()
 *     profileStorage.getById(id)
 * }
 * ```
 *
 * Формат блока (если `facts` не пустой):
 * ```
 * ## User Profile
 * - факт 1
 * - факт 2
 * ```
 * Если у активного профиля `facts` пустой — возвращает `null`.
 *
 * @param getActiveProfile suspend-лямбда, возвращающая текущий активный профиль или null
 */
class ActiveProfileSystemPromptProvider(
    private val getActiveProfile: suspend () -> Profile?
) : ProfileSystemPromptProvider {

    override suspend fun getProfileBlock(): String? {
        val facts = getActiveProfile()
            ?.facts
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        return buildString {
            appendLine("## User Profile")
            facts.forEach { appendLine("- $it") }
        }.trimEnd()
    }
}
