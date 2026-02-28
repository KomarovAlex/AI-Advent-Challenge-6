package ru.koalexse.aichallenge.agent.context.summary

import ru.koalexse.aichallenge.agent.AgentMessage
import ru.koalexse.aichallenge.agent.Role
import ru.koalexse.aichallenge.agent.StatsLLMApi
import ru.koalexse.aichallenge.domain.ApiMessage
import ru.koalexse.aichallenge.domain.ChatRequest
import ru.koalexse.aichallenge.domain.StatsStreamResult

/**
 * Провайдер для генерации summary через LLM API
 *
 * Использует языковую модель для создания краткого описания диалога.
 *
 * @param api API для работы с LLM
 * @param model модель для суммаризации
 * @param summaryPrompt системный промпт для суммаризации
 * @param maxSummaryTokens максимальное количество токенов в summary
 * @param temperature температура генерации (низкая для более точного summary)
 */
class LLMSummaryProvider(
    private val api: StatsLLMApi,
    private val model: String,
    private val summaryPrompt: String = DEFAULT_SUMMARY_PROMPT,
    private val maxSummaryTokens: Long = DEFAULT_MAX_SUMMARY_TOKENS,
    private val temperature: Float = DEFAULT_TEMPERATURE
) : SummaryProvider {

    override suspend fun summarize(messages: List<AgentMessage>): String {
        if (messages.isEmpty()) return ""

        val conversationText = messages.joinToString("\n") { msg ->
            val rolePrefix = when (msg.role) {
                Role.USER -> "User"
                Role.ASSISTANT -> "Assistant"
                Role.SYSTEM -> "System"
            }
            "$rolePrefix: ${msg.content}"
        }

        val request = ChatRequest(
            messages = listOf(
                ApiMessage(role = "system", content = summaryPrompt),
                ApiMessage(role = "user", content = conversationText)
            ),
            model = model,
            temperature = temperature,
            max_tokens = maxSummaryTokens
        )

        val responseBuilder = StringBuilder()

        api.sendMessageStream(request).collect { result ->
            when (result) {
                is StatsStreamResult.Content -> responseBuilder.append(result.text)
                is StatsStreamResult.Stats -> { /* статистику суммаризации игнорируем */ }
            }
        }

        return responseBuilder.toString().trim()
    }

    companion object {
        const val DEFAULT_MAX_SUMMARY_TOKENS = 500L
        const val DEFAULT_TEMPERATURE = 0.3f

        const val DEFAULT_SUMMARY_PROMPT = """You are a conversation summarizer. Your task is to create a concise summary of the conversation below.

Requirements:
- Keep the summary brief but informative (2-4 sentences)
- Preserve key facts, decisions, and context
- Use third person ("The user asked...", "The assistant explained...")
- Focus on information that would be useful for continuing the conversation
- Write in the same language as the conversation

Respond with ONLY the summary, no additional text."""
    }
}

/**
 * Простой fallback-провайдер, создающий summary без LLM.
 *
 * Используется когда LLM недоступен или для тестирования.
 */
class SimpleSummaryProvider(
    private val maxWordsPerMessage: Int = 10,
    private val maxMessages: Int = 5
) : SummaryProvider {

    override suspend fun summarize(messages: List<AgentMessage>): String {
        if (messages.isEmpty()) return ""

        val selectedMessages = messages.takeLast(maxMessages)

        return buildString {
            append("[Summary of ${messages.size} messages]: ")
            selectedMessages.forEachIndexed { index, msg ->
                val rolePrefix = when (msg.role) {
                    Role.USER -> "U"
                    Role.ASSISTANT -> "A"
                    Role.SYSTEM -> "S"
                }
                val truncatedContent = msg.content
                    .split(Regex("\\s+"))
                    .take(maxWordsPerMessage)
                    .joinToString(" ")
                    .let { if (it.length < msg.content.length) "$it..." else it }

                append("$rolePrefix: $truncatedContent")
                if (index < selectedMessages.lastIndex) append(" | ")
            }
        }
    }
}
