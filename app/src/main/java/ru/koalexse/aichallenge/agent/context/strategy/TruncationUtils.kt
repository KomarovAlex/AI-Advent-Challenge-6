package ru.koalexse.aichallenge.agent.context.strategy

import ru.koalexse.aichallenge.agent.AgentMessage

/**
 * Функция оценки количества токенов в сообщении.
 */
typealias TokenEstimator = (AgentMessage) -> Int

/**
 * Стандартные реализации [TokenEstimator].
 */
object TokenEstimators {
    /**
     * Простая эвристика: ~4 символа = 1 токен.
     * Подходит для английского текста; для русского соотношение ~2–3 символа = 1 токен,
     * но для целей обрезки точность не критична.
     */
    val default: TokenEstimator = { message ->
        (message.content.length / 4).coerceAtLeast(1)
    }
}

/**
 * Утилиты обрезки списка сообщений.
 * Используются стратегиями через композицию — без наследования.
 */
object TruncationUtils {

    /**
     * Обрезает список сообщений с конца, пока суммарное количество токенов не уложится
     * в [maxTokens]. Если даже одно последнее сообщение превышает лимит — возвращает его,
     * чтобы не вернуть пустой список.
     *
     * @param messages исходный список (порядок: старые → новые)
     * @param maxTokens максимум токенов
     * @param estimator функция оценки токенов
     * @return усечённый список (новые сообщения, хвост)
     */
    fun truncateByTokens(
        messages: List<AgentMessage>,
        maxTokens: Int,
        estimator: TokenEstimator
    ): List<AgentMessage> {
        if (messages.isEmpty()) return messages
        if (maxTokens <= 0) return listOf(messages.last())

        var totalTokens = 0
        var startIndex = messages.size

        for (i in messages.indices.reversed()) {
            val tokens = estimator(messages[i])
            if (totalTokens + tokens <= maxTokens) {
                totalTokens += tokens
                startIndex = i
            } else {
                break
            }
        }

        return if (startIndex < messages.size) {
            messages.subList(startIndex, messages.size)
        } else {
            listOf(messages.last())
        }
    }
}
