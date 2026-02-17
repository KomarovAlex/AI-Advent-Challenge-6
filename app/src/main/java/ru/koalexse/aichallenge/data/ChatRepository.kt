package ru.koalexse.aichallenge.data

import ru.koalexse.aichallenge.domain.ApiMessage
import ru.koalexse.aichallenge.domain.ChatRequest
import ru.koalexse.aichallenge.domain.Message

class ChatRepository(
    private val api: LLMApi,
    private val model: String,
) {
    suspend fun sendMessage(messages: List<Message>): Result<String> {
        val apiMessages = messages.filter { !it.isLoading }.map {
            ApiMessage(
                role = if (it.isUser) "user" else "assistant",
                content = it.text
            )
        }
        return api.sendMessage(ChatRequest(messages = apiMessages, model = model, max_tokens = 250, stop = listOf("===КОНЕЦ===", "-end-")))
    }
}