package ru.koalexse.aichallenge.ui.state

import ru.koalexse.aichallenge.agent.AgentConfig
import ru.koalexse.aichallenge.agent.context.branch.DialogBranch
import ru.koalexse.aichallenge.agent.context.facts.Fact
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
    SUMMARY
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
    val isBranchDialogOpen: Boolean = false
)

data class SettingsData(
    val model: String,
    val temperature: String? = null,
    val tokens: String? = null,
    val strategy: ContextStrategyType = ContextStrategyType.SUMMARY
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
