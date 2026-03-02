package ru.koalexse.aichallenge.agent.context.memory

import ru.koalexse.aichallenge.agent.AgentMessage

/**
 * Тип слоя памяти ассистента.
 *
 * - [SHORT_TERM]  — скользящее окно текущего диалога; управляется автоматически
 * - [WORKING]     — данные текущей задачи (шаги, промежуточные результаты, переменные);
 *                   обновляется LLM-вызовом автоматически при вытеснении сообщений
 * - [LONG_TERM]   — профиль пользователя, устойчивые решения, долгосрочные знания;
 *                   обновляется только по явному запросу пользователя
 */
enum class MemoryLayer { SHORT_TERM, WORKING, LONG_TERM }

/**
 * Одна запись в памяти ассистента.
 *
 * Используется для [WORKING] и [LONG_TERM] слоёв.
 * [SHORT_TERM] хранится как [AgentMessage] в скользящем окне стратегии и
 * здесь не представлен.
 *
 * @param key       краткое название записи (например, "цель", "шаг 2", "предпочитаемый язык")
 * @param value     содержание записи
 * @param layer     слой, которому принадлежит запись
 * @param updatedAt временная метка последнего обновления (мс)
 */
data class MemoryEntry(
    val key: String,
    val value: String,
    val layer: MemoryLayer,
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Снимок всех трёх слоёв памяти — используется для отображения в UI.
 *
 * ```
 * 🧠 Long-term bubble       ← longTerm (профиль, решения, знания)
 * 💼 Working bubble         ← working  (текущая задача, шаги, результаты)
 * [M1🗜️ … Mk🗜️]            ← compressedMessages (только UI, не идут в LLM)
 * [Mk+1 … Mn]              ← short-term (recent, идут в LLM)
 * ```
 */
data class LayeredMemorySnapshot(
    val shortTerm: List<AgentMessage> = emptyList(),  // recent-окно (из _context)
    val working: List<MemoryEntry> = emptyList(),
    val longTerm: List<MemoryEntry> = emptyList()
)
