package ru.koalexse.aichallenge.data

import kotlinx.coroutines.flow.Flow
import ru.koalexse.aichallenge.domain.ApiMessage
import ru.koalexse.aichallenge.domain.ChatRequest
import ru.koalexse.aichallenge.domain.Message

class ChatRepository(
    private val api: LLMApi,
    private val model: String,
) {
    fun sendMessage(messages: List<Message>): Flow<String> {
        val apiMessages = messages.filter { !it.isLoading }.map {
            ApiMessage(
                role = if (it.isUser) "user" else "assistant",
                content = it.text
            )
        }
        return api.sendMessageStream(ChatRequest(messages = apiMessages, model = model, stop = listOf("===КОНЕЦ===", "-end-")))
    }
}
