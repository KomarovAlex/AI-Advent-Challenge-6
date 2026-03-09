package ru.koalexse.aichallenge.data.mcp

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import ru.koalexse.aichallenge.agent.mcp.McpClient
import ru.koalexse.aichallenge.domain.McpInitializeParams
import ru.koalexse.aichallenge.domain.McpJsonRpcRequest
import ru.koalexse.aichallenge.domain.McpTool
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "KtorMcpClient"
private const val SSE_TIMEOUT_MS = 10_000L

/**
 * Реализация [McpClient] для MCP-сервера с HTTP+SSE транспортом (протокол 2024-11-05).
 *
 * Использует OkHttp SSE (уже есть в проекте) — не требует Ktor.
 *
 * ## Поток работы
 * 1. POST /mcp с методом `initialize` → SSE-событие `endpoint` с URL сессии
 * 2. POST sessionUrl с методом `tools/list` → SSE-событие `message` с JSON-RPC ответом
 *
 * @param baseUrl базовый URL MCP-сервера, например `https://mcp001.vkusvill.ru/mcp`
 */
class KtorMcpClient(
    private val baseUrl: String
) : McpClient {

    private val gson = Gson()
    private var requestId = 0

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun listTools(): List<McpTool> {
        val sessionUrl = initialize() ?: baseUrl
        Log.d(TAG, "Session URL: $sessionUrl")
        return fetchTools(sessionUrl)
    }

    /**
     * Отправляет `initialize` и возвращает URL сессии из SSE-события `endpoint`.
     */
    private suspend fun initialize(): String? = withContext(Dispatchers.IO) {
        val params = gson.toJson(McpInitializeParams())
        val body = gson.toJson(
            McpJsonRpcRequest(id = ++requestId, method = "initialize", params = params)
        )
        Log.d(TAG, "initialize → $body")

        withTimeoutOrNull(SSE_TIMEOUT_MS) {
            postSse(baseUrl, body) { event, data ->
                when (event) {
                    "endpoint" -> resolveEndpoint(data)
                    "message"  -> null // initialize без endpoint — используем baseUrl
                    else       -> null
                }
            }
        }
    }

    /**
     * Отправляет `tools/list` и возвращает список инструментов.
     */
    private suspend fun fetchTools(sessionUrl: String): List<McpTool> =
        withContext(Dispatchers.IO) {
            val body = gson.toJson(
                McpJsonRpcRequest(id = ++requestId, method = "tools/list")
            )
            Log.d(TAG, "tools/list → $body")

            val data = withTimeoutOrNull(SSE_TIMEOUT_MS) {
                postSse(sessionUrl, body) { event, data ->
                    if (event == "message" || event == null) data else null
                }
            }

            if (data == null) {
                Log.w(TAG, "tools/list: no SSE response within timeout")
                return@withContext emptyList()
            }

            parseTools(data)
        }

    /**
     * Выполняет POST-запрос и читает SSE-поток.
     * [handler] вызывается для каждого события; если возвращает non-null — возвращаем это значение.
     */
    private suspend fun <T> postSse(
        url: String,
        jsonBody: String,
        handler: (event: String?, data: String?) -> T?
    ): T? = suspendCancellableCoroutine { cont ->
        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody("application/json".toMediaTypeOrNull()))
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .build()

        val factory = EventSources.createFactory(httpClient)
        val source = factory.newEventSource(request, object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                Log.d(TAG, "SSE event: type=$type data=$data")
                val result = handler(type, data)
                if (result != null) {
                    eventSource.cancel()
                    if (cont.isActive) cont.resume(result)
                } else if (type == "message" || type == "endpoint") {
                    // Событие обработано, но handler вернул null — завершаем
                    eventSource.cancel()
                    if (cont.isActive) cont.resume(null)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                Log.e(TAG, "SSE failure: ${t?.message}, code=${response?.code}")
                eventSource.cancel()
                if (cont.isActive) {
                    if (t != null) cont.resumeWithException(t)
                    else cont.resume(null)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (cont.isActive) cont.resume(null)
            }
        })

        cont.invokeOnCancellation { source.cancel() }
    }

    private fun parseTools(data: String): List<McpTool> =
        runCatching {
            val json = gson.fromJson(data, JsonObject::class.java)
            val result = json.getAsJsonObject("result") ?: return emptyList()
            val array = result.getAsJsonArray("tools") ?: return emptyList()
            array.map { el ->
                val obj = el.asJsonObject
                McpTool(
                    name = obj.get("name")?.asString ?: "",
                    description = obj.get("description")?.asString,
                    inputSchema = obj.get("inputSchema")?.toString()
                )
            }
        }
            .onFailure { Log.e(TAG, "parseTools failed: ${it.message}") }
            .getOrDefault(emptyList())

    private fun resolveEndpoint(endpoint: String?): String? {
        if (endpoint.isNullOrBlank()) return null
        if (endpoint.startsWith("http")) return endpoint
        val origin = baseUrl.substringBefore("/mcp").ifBlank { baseUrl.trimEnd('/') }
        return "$origin/${endpoint.trimStart('/')}"
    }
}
