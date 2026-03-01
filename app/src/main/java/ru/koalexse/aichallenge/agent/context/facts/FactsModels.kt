package ru.koalexse.aichallenge.agent.context.facts

/**
 * Один факт — пара ключ/значение, извлечённая из диалога.
 *
 * @param key   краткое название факта (например, "цель", "язык программирования")
 * @param value содержание факта
 * @param updatedAt время последнего обновления (мс)
 */
data class Fact(
    val key: String,
    val value: String,
    val updatedAt: Long = System.currentTimeMillis()
)
