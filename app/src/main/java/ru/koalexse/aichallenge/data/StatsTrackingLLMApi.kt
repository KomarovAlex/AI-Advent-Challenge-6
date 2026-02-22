package ru.koalexse.aichallenge.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.koalexse.aichallenge.domain.ChatRequest
import ru.koalexse.aichallenge.domain.StatsStreamResult
import ru.koalexse.aichallenge.domain.StreamResult
import ru.koalexse.aichallenge.domain.TokenStats

/**
 * Интерфейс для API с поддержкой статистики времени
 */
interface StatsLLMApi {
    fun sendMessageStream(chatRequest: ChatRequest): Flow<StatsStreamResult>
}

/**
 * Делегат для LLMApi, который добавляет статистику времени к результатам стриминга.
 * 
 * Преобразует StreamResult в StatsStreamResult, добавляя:
 * - время до первого токена (TTFT)
 * - общую длительность запроса
 * 
 * @param delegate оригинальный API, к которому делегируются вызовы
 */
class StatsTrackingLLMApi(
    private val delegate: LLMApi
) : StatsLLMApi {

    override fun sendMessageStream(chatRequest: ChatRequest): Flow<StatsStreamResult> {
        val startTime = System.currentTimeMillis()
        var timeToFirstToken: Long? = null
        
        return delegate.sendMessageStream(chatRequest)
            .map { result ->
                when (result) {
                    is StreamResult.Content -> {
                        if (timeToFirstToken == null) {
                            timeToFirstToken = System.currentTimeMillis() - startTime
                        }
                        StatsStreamResult.Content(result.text)
                    }
                    is StreamResult.TokenUsage -> {
                        val durationMs = System.currentTimeMillis() - startTime
                        StatsStreamResult.Stats(
                            tokenStats = TokenStats(
                                promptTokens = result.usage.prompt_tokens,
                                completionTokens = result.usage.completion_tokens,
                                totalTokens = result.usage.total_tokens,
                                timeToFirstTokenMs = timeToFirstToken
                            ),
                            durationMs = durationMs
                        )
                    }
                }
            }
    }
}
