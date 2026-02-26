package ru.koalexse.aichallenge.agent.context.summary

import ru.koalexse.aichallenge.agent.AgentMessage

/**
 * Summary сжатого блока сообщений
 *
 * Хранит текст summary и оригинальные сообщения, из которых он был создан.
 * Оригинальные сообщения используются только для отображения в UI с пометкой
 * "сжатые" — в запрос к LLM они не включаются.
 *
 * @param content текст summary (отправляется в LLM)
 * @param originalMessages оригинальные сообщения (только для отображения в UI)
 * @param createdAt время создания summary
 */
data class ConversationSummary(
    val content: String,
    val originalMessages: List<AgentMessage>,
    val createdAt: Long = System.currentTimeMillis()
)
