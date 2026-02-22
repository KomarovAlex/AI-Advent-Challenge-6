package ru.koalexse.aichallenge.data

import kotlinx.coroutines.flow.Flow
import ru.koalexse.aichallenge.domain.ApiMessage
import ru.koalexse.aichallenge.domain.ChatRequest
import ru.koalexse.aichallenge.domain.Message
import ru.koalexse.aichallenge.domain.StatsStreamResult

class ChatRepository(
    private val api: StatsLLMApi,
) {
    fun sendMessage(
        messages: List<Message>,
        temperature: Float? = null,
        tokens: Long? = null,
        model: String,
    ): Flow<StatsStreamResult> {
        val apiMessages = messages.filter { !it.isLoading }.map {
            ApiMessage(
                role = if (it.isUser) "user" else "assistant",
                content = it.text
            )
        }
        return api.sendMessageStream(
            ChatRequest(
                messages = apiMessages,
                model = model,
                temperature = temperature,
                max_tokens = tokens,
                stop = listOf("===КОНЕЦ===", "-end-")
            )
        )
    }
}
