package ru.koalexse.aichallenge.domain

// Минимальные модели данных
data class Message(
    val id: String = System.currentTimeMillis().toString(),
    val isUser: Boolean,
    val text: String,
    val isLoading: Boolean = false
)

data class ChatRequest(
    val messages: List<ApiMessage>
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