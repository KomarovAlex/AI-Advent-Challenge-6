package ru.koalexse.aichallenge.data

import ru.koalexse.aichallenge.domain.Message

class ChatRepository(
    private val api: LLMApi,
) {
    suspend fun sendMessage(messages: List<Message>): Result<String> {
        return api.sendMessage(messages)
    }
}