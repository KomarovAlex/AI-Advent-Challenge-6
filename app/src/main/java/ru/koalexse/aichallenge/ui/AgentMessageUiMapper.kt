package ru.koalexse.aichallenge.ui

import ru.koalexse.aichallenge.agent.AgentMessage
import ru.koalexse.aichallenge.agent.Role
import ru.koalexse.aichallenge.agent.context.memory.MemoryEntry
import ru.koalexse.aichallenge.agent.context.summary.ConversationSummary
import ru.koalexse.aichallenge.agent.isUser
import ru.koalexse.aichallenge.agent.task.TaskState
import ru.koalexse.aichallenge.domain.Message
import ru.koalexse.aichallenge.domain.TokenStats
import ru.koalexse.aichallenge.ui.state.displayName

/**
 * Конвертеры агентных моделей в UI-модели.
 *
 * Живут в ui/ — знают про isCompressed, isLoading и другие UI-концепты,
 * которые не должны проникать в agent/.
 */

/**
 * Конвертирует одно сообщение агента в UI-модель.
 */
fun AgentMessage.toUiMessage(
    id: String,
    tokenStats: TokenStats? = null,
    responseDurationMs: Long? = null,
    isCompressed: Boolean = false
): Message = Message(
    id = id,
    isUser = role == Role.USER,
    text = content,
    isLoading = false,
    tokenStats = tokenStats,
    responseDurationMs = responseDurationMs,
    isCompressed = isCompressed
)

/**
 * Конвертирует список summaries в UI-сообщения с пометкой isCompressed=true.
 * Порядок: summary 0 → summary 1 → ... (хронологический).
 */
fun List<ConversationSummary>.toCompressedUiMessages(): List<Message> =
    flatMapIndexed { summaryIndex, summary ->
        summary.originalMessages.mapIndexed { msgIndex, agentMessage ->
            agentMessage.toUiMessage(
                id = "compressed_${summaryIndex}_${msgIndex}",
                isCompressed = true
            )
        }
    }

/**
 * Конвертирует flat-список compressed [AgentMessage] (от StickyFactsStrategy) в UI-сообщения
 * с пометкой isCompressed=true.
 *
 * Используется для отображения сообщений, вытесненных из LLM-контекста стратегией
 * [ru.koalexse.aichallenge.agent.context.strategy.StickyFactsStrategy], — они хранятся
 * только для UI и в LLM-запрос не включаются.
 */
fun List<AgentMessage>.toFactsCompressedUiMessages(): List<Message> =
    mapIndexed { index, agentMessage ->
        agentMessage.toUiMessage(
            id = "facts_compressed_$index",
            isCompressed = true
        )
    }

/**
 * Конвертирует flat-список compressed [AgentMessage] (от LayeredMemoryStrategy) в UI-сообщения
 * с пометкой isCompressed=true.
 *
 * Используется для отображения сообщений, вытесненных из LLM-контекста стратегией
 * [ru.koalexse.aichallenge.agent.context.strategy.LayeredMemoryStrategy], — они хранятся
 * только для UI и в LLM-запрос не включаются.
 */
fun List<AgentMessage>.toMemoryCompressedUiMessages(): List<Message> =
    mapIndexed { index, agentMessage ->
        agentMessage.toUiMessage(
            id = "memory_compressed_$index",
            isCompressed = true
        )
    }

/**
 * Конвертирует список записей рабочей памяти в специальный UI-Message
 * для отображения в [WorkingMemoryBubble].
 *
 * Возвращает `null` если список пуст.
 */
fun List<MemoryEntry>.toWorkingMemoryUiMessage(): Message? {
    if (isEmpty()) return null
    val text = joinToString("\n") { "• ${it.key}: ${it.value}" }
    return Message(
        id = "working_memory_bubble",
        isUser = false,
        text = text,
        isCompressed = true
    )
}

/**
 * Конвертирует список записей долговременной памяти в специальный UI-Message
 * для отображения в [LongTermMemoryBubble].
 *
 * Возвращает `null` если список пуст.
 */
fun List<MemoryEntry>.toLongTermMemoryUiMessage(): Message? {
    if (isEmpty()) return null
    val text = joinToString("\n") { "• ${it.key}: ${it.value}" }
    return Message(
        id = "long_term_memory_bubble",
        isUser = false,
        text = text,
        isCompressed = true
    )
}

/**
 * Конвертирует [TaskState] в специальный UI-Message для отображения в [TaskStateBubble].
 *
 * Bubble отображается только в Planning mode когда задача активна.
 * Содержит:
 * - текущую фазу + шаг + expectedAction
 * - итоги завершённых фаз ([TaskState.phaseOutputs])
 * - инварианты текущей фазы
 *
 * Текст специально форматирован для парсинга в [TaskStateBubble] —
 * bubble не использует raw text напрямую, он читает поля из [TaskState].
 * Message здесь — только транспорт для id и маршрутизации в [MessageList].
 *
 * Возвращает `null` если задача неактивна.
 */
fun TaskState.toTaskStateBubbleMessage(): Message? {
    if (!isActive) return null

    val sb = StringBuilder()

    // Текущая фаза
    sb.appendLine("${phase.displayName()} · ${currentStep}")
    if (expectedAction.isNotBlank()) {
        sb.appendLine("Expected: $expectedAction")
    }

    // Инварианты текущей фазы
    val invariants = currentInvariants
    if (invariants.isNotEmpty()) {
        sb.appendLine()
        sb.appendLine("Invariants:")
        invariants.forEach { sb.appendLine("• $it") }
    }

    // Итоги завершённых фаз
    if (phaseOutputs.isNotEmpty()) {
        sb.appendLine()
        sb.appendLine("Completed phases:")
        phaseOutputs.forEach { po ->
            sb.appendLine("${po.phase.displayName()}: ${po.output.take(120).trimEnd()}…")
        }
    }

    return Message(
        id = TASK_STATE_BUBBLE_ID,
        isUser = false,
        text = sb.toString().trimEnd(),
        isCompressed = true
    )
}

/**
 * Конвертирует активную историю агента в UI-сообщения.
 * Последнему ответу ассистента проставляются [lastMessageStats] и [lastMessageDuration].
 */
fun List<AgentMessage>.toActiveUiMessages(
    lastMessageStats: TokenStats? = null,
    lastMessageDuration: Long? = null
): List<Message> {
    val lastAssistantIndex = indexOfLast { !it.isUser }
    return mapIndexed { index, agentMessage ->
        val isLastAssistant = index == lastAssistantIndex && !agentMessage.isUser
        agentMessage.toUiMessage(
            id = "msg_$index",
            tokenStats = if (isLastAssistant) lastMessageStats else null,
            responseDurationMs = if (isLastAssistant) lastMessageDuration else null
        )
    }
}

/** Id bubble-сообщения с состоянием задачи. Используется для маршрутизации в [MessageList]. */
const val TASK_STATE_BUBBLE_ID = "task_state_bubble"
