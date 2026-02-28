package ru.koalexse.aichallenge.ui

import ru.koalexse.aichallenge.agent.AgentMessage
import ru.koalexse.aichallenge.agent.Role
import ru.koalexse.aichallenge.agent.context.summary.ConversationSummary
import ru.koalexse.aichallenge.agent.isUser
import ru.koalexse.aichallenge.domain.Message
import ru.koalexse.aichallenge.domain.TokenStats

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
