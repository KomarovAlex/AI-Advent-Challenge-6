package ru.koalexse.aichallenge.agent.task

/**
 * Фазы конечного автомата задачи.
 *
 * Переходы:
 * ```
 * PLANNING → EXECUTION → VALIDATION → DONE → (новая задача: PLANNING)
 * ```
 *
 * Каждая фаза может быть приостановлена (пауза между сессиями) и продолжена без
 * повторного объяснения контекста — состояние персистируется в [TaskStateStorage].
 */
enum class TaskPhase {
    /** Постановка цели, декомпозиция, выбор подхода */
    PLANNING,

    /** Выполнение шагов задачи */
    EXECUTION,

    /** Проверка результата на соответствие цели и инвариантам */
    VALIDATION,

    /** Задача завершена. После архивирования можно начать новую. */
    DONE
}

/**
 * Инварианты для одной фазы задачи.
 *
 * @param phase   фаза, к которой относятся инварианты
 * @param rules   список правил/ограничений, которые должны соблюдаться в данной фазе
 */
data class PhaseInvariants(
    val phase: TaskPhase,
    val rules: List<String>
)

/**
 * Итог одной завершённой фазы — передаётся в контекст следующей фазы.
 *
 * Записывается при переходе фазы: LLM добавляет тег `[OUTPUT: <текст>]` в конце
 * ответа, агент извлекает его и сохраняет как [PhaseOutput]. Если тег отсутствует —
 * используется fallback: последние N символов ответа.
 *
 * ### Зачем нужен
 * Без [PhaseOutput] LLM на фазе EXECUTION видит только скользящее окно истории —
 * план из PLANNING может уже «вытечь» за границу `keepRecentCount`. [PhaseOutput]
 * гарантирует, что ключевые решения каждой фазы всегда присутствуют в system-промпте.
 *
 * ### Жизненный цикл
 * - Создаётся: при переходе из фазы (в [TaskStateMachineAgent.transitionToPhase])
 * - Хранится: в [TaskState.phaseOutputs] (персистируется в `task_state.json`)
 * - Используется: в [TaskStateMachineAgent.buildSystemPromptBlock] (секция `## Completed phases`)
 * - Сбрасывается: при [TaskStateMachineAgent.resetTask] (вместе с [TaskState])
 *
 * @param phase       фаза, итог которой зафиксирован
 * @param output      краткий итог фазы (1–3 абзаца); извлекается из тега `[OUTPUT: ...]`
 *                    или берётся как последние символы ответа LLM
 * @param completedAt время завершения фазы (мс)
 */
data class PhaseOutput(
    val phase: TaskPhase,
    val output: String,
    val completedAt: Long = System.currentTimeMillis()
)

/**
 * Полное состояние задачи — единица персистентности.
 *
 * ### Поля
 * @param taskId          уникальный идентификатор задачи (UUID)
 * @param phase           текущая фаза автомата
 * @param currentStep     текстовое описание текущего шага (что делаем прямо сейчас)
 * @param expectedAction  что ожидается от пользователя или LLM на этом шаге
 * @param phaseInvariants инварианты по фазам (может быть задан subset фаз)
 * @param phaseOutputs    итоги завершённых фаз; передаются в system-промпт следующих фаз
 * @param isActive        true = есть активная задача; false = нет задачи (начальное состояние)
 * @param retryCount      сколько раз подряд провалилась валидация текущего ответа LLM
 * @param createdAt       время создания задачи (мс)
 * @param updatedAt       время последнего обновления состояния (мс)
 * @param archivedTasks   список завершённых задач (история между задачами)
 */
data class TaskState(
    val taskId: String = "",
    val phase: TaskPhase = TaskPhase.PLANNING,
    val currentStep: String = "",
    val expectedAction: String = "",
    val phaseInvariants: List<PhaseInvariants> = emptyList(),
    val phaseOutputs: List<PhaseOutput> = emptyList(),
    val isActive: Boolean = false,
    val retryCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val archivedTasks: List<ArchivedTask> = emptyList()
) {
    /** Возвращает инварианты для текущей фазы (пустой список если не заданы). */
    val currentInvariants: List<String>
        get() = phaseInvariants.firstOrNull { it.phase == phase }?.rules ?: emptyList()

    /** Возвращает итог указанной фазы, или null если фаза ещё не завершена. */
    fun outputFor(phase: TaskPhase): String? =
        phaseOutputs.firstOrNull { it.phase == phase }?.output
}

/**
 * Архивная запись завершённой задачи.
 *
 * Отличие от [PhaseOutput]: [ArchivedTask] — итог **всей задачи** целиком,
 * хранится между задачами. [PhaseOutput] — итог **одной фазы**, хранится
 * только внутри активной задачи и сбрасывается при [TaskStateMachineAgent.resetTask].
 *
 * @param taskId      идентификатор задачи
 * @param summary     краткое описание того, что было сделано (заполняется при DONE)
 * @param completedAt время завершения (мс)
 */
data class ArchivedTask(
    val taskId: String,
    val summary: String,
    val completedAt: Long = System.currentTimeMillis()
)

/**
 * Сигнал перехода между фазами, извлечённый из ответа LLM.
 *
 * LLM возвращает в конце ответа один из тегов:
 * - `[PHASE_COMPLETE]`         — текущая фаза завершена, перейти к следующей
 * - `[PHASE: execution]`       — предложить конкретную следующую фазу
 * - `[STEP: <описание>]`       — обновить текущий шаг без смены фазы
 * - `[EXPECTED: <действие>]`   — обновить ожидаемое действие
 * - `[OUTPUT: <итог>]`         — зафиксировать итог фазы (парсится отдельно, не через TaskSignal)
 * - ничего                     — состояние не меняется
 */
sealed class TaskSignal {
    /** Текущая фаза завершена → переход к следующей по порядку [TaskPhase] */
    data object PhaseComplete : TaskSignal()

    /** LLM предлагает перейти к конкретной фазе */
    data class SuggestPhase(val next: TaskPhase) : TaskSignal()

    /** Обновить текущий шаг (без смены фазы) */
    data class UpdateStep(val step: String) : TaskSignal()

    /** Обновить ожидаемое действие (без смены фазы) */
    data class UpdateExpected(val expected: String) : TaskSignal()

    /** Нет сигнала — состояние не меняется */
    data object None : TaskSignal()
}

/**
 * Результат валидации ответа LLM на соответствие инвариантам текущей фазы.
 *
 * @param isValid    true = инварианты соблюдены, ответ можно отправить пользователю
 * @param violations список нарушенных инвариантов (пустой если [isValid] = true)
 */
data class ValidationResult(
    val isValid: Boolean,
    val violations: List<String> = emptyList()
)
