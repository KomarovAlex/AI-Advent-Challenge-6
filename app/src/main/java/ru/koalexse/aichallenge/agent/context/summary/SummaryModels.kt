package ru.koalexse.aichallenge.agent.context.summary

/**
 * Summary сжатого блока сообщений
 * 
 * @param content текст summary
 * @param originalMessageCount количество сообщений, из которых создано summary
 * @param createdAt время создания summary
 */
data class ConversationSummary(
    val content: String,
    val originalMessageCount: Int,
    val createdAt: Long = System.currentTimeMillis()
)
