package ru.koalexse.aichallenge.domain

// Минимальные модели данных
data class Message(
    val id: String = System.currentTimeMillis().toString(),
    val isUser: Boolean,
    val text: String,
    val isLoading: Boolean = false,
    val tokenStats: TokenStats? = null,
    val responseDurationMs: Long? = null
)

data class TokenStats(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val timeToFirstTokenMs: Long? = null,
    // Токены только истории (без текущего сообщения пользователя)
    val historyTokens: Int? = null
)

/**
 * Накопительная статистика токенов за сессию
 */
data class SessionTokenStats(
    val totalPromptTokens: Int = 0,
    val totalCompletionTokens: Int = 0,
    val totalTokens: Int = 0,
    val messageCount: Int = 0
) {
    /**
     * Добавляет статистику от нового запроса
     */
    fun add(tokenStats: TokenStats): SessionTokenStats = SessionTokenStats(
        totalPromptTokens = totalPromptTokens + tokenStats.promptTokens,
        totalCompletionTokens = totalCompletionTokens + tokenStats.completionTokens,
        totalTokens = totalTokens + tokenStats.totalTokens,
        messageCount = messageCount + 1
    )
}

data class ChatRequest(
    val messages: List<ApiMessage>,
    val model: String,
    val stop: List<String>? = null,
    val max_tokens: Long? = null,
    val temperature: Float? = null,
    val stream: Boolean = false,
    val stream_options: StreamOptions? = null,
)

data class StreamOptions(
    val include_usage: Boolean = true
)

data class ApiMessage(
    val role: String,
    val content: String
)

data class ChatResponse(
    val choices: List<Choice>,
    val usage: Usage? = null
)

data class Choice(
    val message: ApiMessage? = null,
    val delta: Delta? = null
)

data class Delta(
    val content: String? = null
)

data class Usage(
    val prompt_tokens: Int = 0,
    val completion_tokens: Int = 0,
    val total_tokens: Int = 0
)

// Sealed class для результатов стриминга
sealed class StreamResult {
    data class Content(val text: String) : StreamResult()
    data class TokenUsage(val usage: Usage) : StreamResult()
}

// Результат с полной статистикой (используется после обработки StatsTrackingLLMApi)
sealed class StatsStreamResult {
    data class Content(val text: String) : StatsStreamResult()
    data class Stats(val tokenStats: TokenStats, val durationMs: Long) : StatsStreamResult()
}
