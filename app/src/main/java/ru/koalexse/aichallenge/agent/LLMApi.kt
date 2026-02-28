package ru.koalexse.aichallenge.agent

import kotlinx.coroutines.flow.Flow
import ru.koalexse.aichallenge.domain.ChatRequest
import ru.koalexse.aichallenge.domain.StatsStreamResult

/**
 * Интерфейс API для работы с LLM.
 *
 * Живёт в слое `agent/`, чтобы агент зависел только от domain и собственных абстракций.
 * Реализация ([ru.koalexse.aichallenge.data.StatsTrackingLLMApi]) находится в `data/`
 * и подключается через DI.
 *
 * Зависимости: agent/ → domain/  (data/ реализует этот интерфейс, не наоборот)
 */
interface StatsLLMApi {
    fun sendMessageStream(chatRequest: ChatRequest): Flow<StatsStreamResult>
}
