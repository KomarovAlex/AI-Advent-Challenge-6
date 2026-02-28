package ru.koalexse.aichallenge.agent

import ru.koalexse.aichallenge.domain.TokenStats

/**
 * Роль участника в диалоге
 */
enum class Role {
    USER,
    ASSISTANT,
    SYSTEM
}

/**
 * Сообщение в контексте агента
 * Упрощённая модель, не зависящая от UI
 */
data class AgentMessage(
    val role: Role,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

val AgentMessage.isUser: Boolean
    get() = role == Role.USER

/**
 * Запрос к агенту
 *
 * История диалога не передаётся в запросе — агент управляет ею самостоятельно
 * через внутренний контекст. Запрос содержит только параметры конкретного вызова.
 *
 * @param userMessage текст сообщения пользователя
 * @param systemPrompt системный промпт для настройки поведения агента (опционально,
 *                     переопределяет [AgentConfig.defaultSystemPrompt])
 * @param model название модели LLM
 * @param temperature температура генерации (0.0–2.0)
 * @param maxTokens максимальное количество токенов в ответе
 * @param stopSequences последовательности для остановки генерации
 */
data class AgentRequest(
    val userMessage: String,
    val systemPrompt: String? = null,
    val model: String,
    val temperature: Float? = null,
    val maxTokens: Long? = null,
    val stopSequences: List<String>? = null
)

/**
 * Полный ответ агента (для не-стримингового режима)
 *
 * @param content полный текст ответа
 * @param tokenStats статистика использования токенов
 * @param durationMs время генерации ответа в миллисекундах
 * @param model использованная модель
 */
data class AgentResponse(
    val content: String,
    val tokenStats: TokenStats? = null,
    val durationMs: Long? = null,
    val model: String
)

/**
 * Результаты стриминга ответа агента
 */
sealed class AgentStreamEvent {
    /**
     * Частичный контент (чанк текста)
     */
    data class ContentDelta(val text: String) : AgentStreamEvent()

    /**
     * Финальная статистика после завершения генерации
     */
    data class Completed(
        val tokenStats: TokenStats,
        val durationMs: Long
    ) : AgentStreamEvent()

    /**
     * Ошибка во время генерации
     */
    data class Error(val exception: Throwable) : AgentStreamEvent()
}

/**
 * Конфигурация агента
 *
 * @param defaultModel модель по умолчанию
 * @param defaultTemperature температура по умолчанию
 * @param defaultMaxTokens максимум токенов в ответе по умолчанию
 * @param defaultSystemPrompt системный промпт по умолчанию
 * @param defaultStopSequences стоп-последовательности по умолчанию
 * @param keepConversationHistory сохранять ли историю диалога внутри агента
 * @param maxHistorySize максимальный размер истории (количество сообщений, null = без ограничения)
 * @param maxTokens максимальный размер истории в токенах (null = без ограничения)
 */
data class AgentConfig(
    val defaultModel: String,
    val defaultTemperature: Float? = null,
    val defaultMaxTokens: Long? = null,
    val defaultSystemPrompt: String? = null,
    val defaultStopSequences: List<String>? = null,
    val keepConversationHistory: Boolean = true,
    val maxHistorySize: Int? = null,
    val maxTokens: Int? = null
)
