package ru.koalexse.aichallenge.domain

// Минимальные модели данных
data class Message(
    val id: String = System.currentTimeMillis().toString(),
    val isUser: Boolean,
    val text: String,
    val isLoading: Boolean = false
)

data class ChatRequest(
    val messages: List<ApiMessage>,
    val model: String,
    val stop: String? = null,
    val max_tokens: Long? = null,
    val temperature: Float? = null,
)

data class ApiMessage(
    val role: String,
    val content: String
)

data class ChatResponse(
    val choices: List<Choice>
)

data class Choice(
    val message: ApiMessage
)