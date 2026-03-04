package ru.koalexse.aichallenge.ui.state

import ru.koalexse.aichallenge.agent.AgentConfig
import ru.koalexse.aichallenge.agent.AgentMessage
import ru.koalexse.aichallenge.agent.context.branch.DialogBranch
import ru.koalexse.aichallenge.agent.context.facts.Fact
import ru.koalexse.aichallenge.agent.context.memory.MemoryEntry
import ru.koalexse.aichallenge.agent.task.TaskPhase
import ru.koalexse.aichallenge.agent.task.TaskState
import ru.koalexse.aichallenge.domain.Message
import ru.koalexse.aichallenge.domain.SessionTokenStats

/**
 * Тип активной стратегии управления контекстом.
 */
enum class ContextStrategyType {
    /** Скользящее окно: хранятся только последние N сообщений */
    SLIDING_WINDOW,

    /** Sticky Facts: ключевые факты + последние N сообщений */
    STICKY_FACTS,

    /** Branching: ветки диалога */
    BRANCHING,

    /** Summary: компрессия через LLM-суммаризацию (существующая стратегия) */
    SUMMARY,

    /**
     * Layered Memory: явная трёхслойная модель памяти.
     * - SHORT_TERM  — скользящее окно (в LLM как история)
     * - WORKING     — текущая задача, шаги, результаты (в LLM как system)
     * - LONG_TERM   — профиль, решения, знания (в LLM как system)
     */
    LAYERED_MEMORY,

    /**
     * Task State Machine: конечный автомат задачи.
     * Фазы: PLANNING → EXECUTION → VALIDATION → DONE
     * Каждый ответ LLM валидируется на соответствие инвариантам фазы.
     * Состояние персистируется между сессиями (task_state.json).
     */
    TASK_STATE_MACHINE
}

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val availableModels: List<String> = emptyList(),
    val settingsData: SettingsData,
    val currentInput: String = "",
    val isLoading: Boolean = false,
    val isSettingsOpen: Boolean = false,
    val error: String? = null,
    val sessionStats: SessionTokenStats? = null,
    /**
     * Количество сообщений, сжатых в summaries
     */
    val compressedMessageCount: Int = 0,

    // ==================== Стратегии ====================

    /** Активная стратегия управления контекстом */
    val activeStrategy: ContextStrategyType = ContextStrategyType.SUMMARY,

    // ==================== Sticky Facts ====================

    /** Текущие факты (только для StickyFacts-стратегии) */
    val facts: List<Fact> = emptyList(),
    /** true пока идёт LLM-вызов обновления фактов */
    val isRefreshingFacts: Boolean = false,

    // ==================== Branching ====================

    /** Список веток (только для Branching-стратегии) */
    val branches: List<DialogBranch> = emptyList(),
    /** Id активной ветки */
    val activeBranchId: String? = null,
    /** true если достигнут лимит веток */
    val isBranchLimitReached: Boolean = false,
    /** true пока идёт переключение ветки */
    val isSwitchingBranch: Boolean = false,
    /** true если открыт диалог переключения веток */
    val isBranchDialogOpen: Boolean = false,

    // ==================== Layered Memory ====================

    /**
     * Записи рабочей памяти WORKING (текущая задача, шаги, результаты).
     * Только для LAYERED_MEMORY-стратегии.
     */
    val workingMemory: List<MemoryEntry> = emptyList(),

    /**
     * Записи долговременной памяти LONG_TERM (профиль, решения, знания).
     * Только для LAYERED_MEMORY-стратегии.
     */
    val longTermMemory: List<MemoryEntry> = emptyList(),

    /**
     * Сообщения, вытесненные из LLM-контекста стратегией LayeredMemory.
     * Только для UI — в LLM не идут.
     */
    val memoryCompressedMessages: List<AgentMessage> = emptyList(),

    /** true пока идёт LLM-вызов обновления WORKING-памяти */
    val isRefreshingWorkingMemory: Boolean = false,

    /** true пока идёт LLM-вызов обновления LONG_TERM-памяти */
    val isRefreshingLongTermMemory: Boolean = false,

    // ==================== Task State Machine ====================

    /**
     * Текущее состояние задачи (только для TASK_STATE_MACHINE).
     * null = нет активной задачи.
     */
    val taskState: TaskState? = null,

    /** true пока идёт LLM-валидация ответа или переход между фазами */
    val isValidatingTask: Boolean = false,

    /** Сообщение об ошибке валидации инвариантов (null = нет ошибки) */
    val taskValidationError: String? = null,

    /** true пока идёт ручной переход к следующей фазе (AdvancePhase) */
    val isAdvancingPhase: Boolean = false,

    /** Открыт ли диалог запуска новой задачи (ввод инвариантов) */
    val isStartTaskDialogOpen: Boolean = false
)

data class SettingsData(
    val model: String,
    val temperature: String? = null,
    val tokens: String? = null,
    val strategy: ContextStrategyType = ContextStrategyType.SUMMARY,
    /** Максимальное число повторных попыток при нарушении инвариантов (TASK_STATE_MACHINE) */
    val maxRetries: String? = null
)

fun AgentConfig.toSettingsData(strategy: ContextStrategyType = ContextStrategyType.SUMMARY) =
    SettingsData(
        model = defaultModel,
        temperature = defaultTemperature?.toString(),
        tokens = defaultMaxTokens?.toString(),
        strategy = strategy
    )

fun SettingsData.isEmpty() =
    model.isEmpty() && temperature.isNullOrEmpty() && tokens.isNullOrEmpty()

// ==================== Отображаемые имена фаз ====================

fun TaskPhase.displayName(): String = when (this) {
    TaskPhase.PLANNING   -> "🗺️ Planning"
    TaskPhase.EXECUTION  -> "⚙️ Execution"
    TaskPhase.VALIDATION -> "✅ Validation"
    TaskPhase.DONE       -> "🏁 Done"
}
