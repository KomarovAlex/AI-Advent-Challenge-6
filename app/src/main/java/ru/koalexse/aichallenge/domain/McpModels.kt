package ru.koalexse.aichallenge.domain

/**
 * MCP-инструмент, возвращаемый сервером при вызове tools/list.
 *
 * @param name        уникальное имя инструмента
 * @param description описание (опционально)
 * @param inputSchema JSON-схема входных параметров в виде строки (опционально)
 */
data class McpTool(
    val name: String,
    val description: String? = null,
    val inputSchema: String? = null
)

/**
 * JSON-RPC 2.0 запрос к MCP-серверу.
 */
data class McpJsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Int,
    val method: String,
    val params: Any? = null
)

/**
 * Параметры инициализации MCP-сессии (протокол 2024-11-05).
 */
data class McpInitializeParams(
    val protocolVersion: String = "2024-11-05",
    val capabilities: Map<String, Any> = emptyMap(),
    val clientInfo: Map<String, String> = mapOf(
        "name" to "aiChallenge",
        "version" to "1.0"
    )
)
