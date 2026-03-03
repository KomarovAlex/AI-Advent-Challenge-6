package ru.koalexse.aichallenge.agent

import ru.koalexse.aichallenge.agent.context.strategy.ContextTruncationStrategy
import ru.koalexse.aichallenge.agent.profile.ProfileSystemPromptProvider
import ru.koalexse.aichallenge.domain.ApiMessage

/**
 * Стандартная реализация [PromptBuilder].
 *
 * Формирует список сообщений для LLM-запроса согласно соглашению проекта:
 *
 * ```
 * 1. [system]  блоки объединяются в ОДНО system-сообщение через "\n\n":
 *              1а. ## User Profile (от profilePromptProvider, если facts не пусты)
 *              1б. systemPrompt из request (приоритет) или config.defaultSystemPrompt
 *              1в. getAdditionalSystemMessages() от стратегии (summary / facts)
 *              → если все блоки пусты — system-сообщение не добавляется
 *
 * 2a. keepConversationHistory=true  → история из historyProvider, Role.SYSTEM отфильтрованы
 * 2b. keepConversationHistory=false → только текущий userMessage
 * ```
 *
 * Зависимости передаются через конструктор в виде лямбд, чтобы всегда читать
 * актуальные значения: стратегия может быть заменена через [Agent.updateTruncationStrategy],
 * история меняется при каждом сообщении.
 *
 * Не зависит от Android-фреймворка — тестируется без эмулятора.
 *
 * @param profilePromptProvider провайдер блока профиля пользователя (null = без профиля)
 * @param truncationStrategyProvider возвращает актуальную стратегию обрезки контекста
 * @param historyProvider возвращает текущую историю из AgentContext
 */
class DefaultPromptBuilder(
    private val profilePromptProvider: ProfileSystemPromptProvider? = null,
    private val truncationStrategyProvider: () -> ContextTruncationStrategy? = { null },
    private val historyProvider: () -> List<AgentMessage> = { emptyList() }
) : PromptBuilder {

    override suspend fun buildMessages(
        request: AgentRequest,
        config: AgentConfig
    ): List<ApiMessage> {
        val messages = mutableListOf<ApiMessage>()
        val systemPrompts = mutableListOf<String>()

        // 1а. Блок профиля — первый, до системного промпта и стратегии
        profilePromptProvider?.getProfileBlock()
            ?.let { systemPrompts.add(it) }

        // 1б. Системный промпт: из запроса или из снимка конфига
        val systemPrompt = request.systemPrompt ?: config.defaultSystemPrompt
        if (!systemPrompt.isNullOrBlank()) {
            systemPrompts.add(systemPrompt)
        }

        // 1в. Дополнительные системные сообщения от стратегии (summary, facts и т.п.)
        truncationStrategyProvider()?.getAdditionalSystemMessages()
            ?.map { it.content }
            ?.filter { it.isNotBlank() }
            ?.let { systemPrompts.addAll(it) }

        // Добавляем объединённый system-промпт, если есть что добавлять
        if (systemPrompts.isNotEmpty()) {
            messages.add(ApiMessage(role = "system", content = systemPrompts.joinToString("\n\n")))
        }

        // 2. История или одиночный запрос
        if (config.keepConversationHistory) {
            // Исключаем Role.SYSTEM из истории — они уже учтены в объединённом блоке выше,
            // чтобы не дублировать system-инструкции
            historyProvider()
                .filter { it.role != Role.SYSTEM }
                .forEach { messages.add(it.toApiMessage()) }
        } else {
            messages.add(ApiMessage(role = "user", content = request.userMessage))
        }

        return messages
    }
}
