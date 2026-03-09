package ru.koalexse.aichallenge.agent.mcp

import ru.koalexse.aichallenge.domain.McpTool

/**
 * Контракт MCP-клиента.
 *
 * Интерфейс живёт в `agent/` — без Android-зависимостей.
 * Реализация ([KtorMcpClient]) живёт в `data/mcp/`.
 */
interface McpClient {
    /** Возвращает список инструментов, доступных на MCP-сервере. */
    suspend fun listTools(): List<McpTool>
}
