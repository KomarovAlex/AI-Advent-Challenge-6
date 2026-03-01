package ru.koalexse.aichallenge.agent.context.strategy

import ru.koalexse.aichallenge.agent.AgentMessage
import ru.koalexse.aichallenge.agent.Role

/**
 * Стратегия обрезки, сохраняющая системные сообщения.
 *
 * При обрезке системные сообщения (role = SYSTEM) всегда сохраняются.
 * Удаляются только USER и ASSISTANT, начиная с самых старых.
 *
 * Полезна для сохранения важных инструкций и настроек поведения агента,
 * которые хранятся непосредственно в истории (не в конфиге).
 *
 * @param tokenEstimator функция оценки токенов. По умолчанию — [TokenEstimators.default].
 */
class PreserveSystemTruncationStrategy(
    private val tokenEstimator: TokenEstimator = TokenEstimators.default
) : ContextTruncationStrategy {

    override suspend fun truncate(
        messages: List<AgentMessage>,
        maxTokens: Int?,
        maxMessages: Int?
    ): List<AgentMessage> {
        if (messages.isEmpty()) return messages

        val systemMessages = messages.filter { it.role == Role.SYSTEM }
        val nonSystemMessages = messages.filter { it.role != Role.SYSTEM }

        var truncatedNonSystem = nonSystemMessages

        if (maxMessages != null) {
            val maxNonSystem = (maxMessages - systemMessages.size).coerceAtLeast(0)
            if (truncatedNonSystem.size > maxNonSystem) {
                truncatedNonSystem = truncatedNonSystem.takeLast(maxNonSystem)
            }
        }

        if (maxTokens != null) {
            val systemTokens = systemMessages.sumOf { tokenEstimator(it) }
            val availableTokens = (maxTokens - systemTokens).coerceAtLeast(0)
            truncatedNonSystem = TruncationUtils.truncateByTokens(
                messages = truncatedNonSystem,
                maxTokens = availableTokens,
                estimator = tokenEstimator
            )
        }

        // Восстанавливаем порядок: системные сообщения сохраняют исходные позиции
        val systemSet = systemMessages.toSet()
        val nonSystemSet = truncatedNonSystem.toSet()
        return messages.filter { it in systemSet || it in nonSystemSet }
    }
}
