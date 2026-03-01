package ru.koalexse.aichallenge.agent.context.branch

import ru.koalexse.aichallenge.agent.AgentMessage
import ru.koalexse.aichallenge.agent.context.summary.ConversationSummary

/**
 * Одна ветка диалога.
 *
 * @param id       уникальный идентификатор ветки
 * @param name     отображаемое имя (например, "Branch 1")
 * @param messages история сообщений этой ветки
 * @param summaries summaries этой ветки (если применялась компрессия)
 * @param createdAt время создания
 */
data class DialogBranch(
    val id: String,
    val name: String,
    val messages: List<AgentMessage>,
    val summaries: List<ConversationSummary> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)
