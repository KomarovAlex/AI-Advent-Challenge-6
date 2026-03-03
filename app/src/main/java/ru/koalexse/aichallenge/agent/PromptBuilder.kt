package ru.koalexse.aichallenge.agent

import ru.koalexse.aichallenge.domain.ApiMessage

/**
 * Формирует список сообщений для отправки в LLM API.
 *
 * Отвечает за сборку system-промпта (блок профиля, системный промпт, сообщения стратегии)
 * и добавление истории диалога или одиночного сообщения пользователя.
 *
 * Используется в [SimpleLLMAgent.buildChatRequest] — агент делегирует
 * всю логику формирования сообщений сюда.
 *
 * @see DefaultPromptBuilder стандартная реализация
 */
interface PromptBuilder {

    /**
     * Формирует упорядоченный список сообщений для LLM-запроса.
     *
     * @param request параметры текущего запроса
     * @param config снимок конфигурации агента (snapshot — не читать _config напрямую)
     * @return список [ApiMessage] в порядке: system → история / userMessage
     */
    suspend fun buildMessages(
        request: AgentRequest,
        config: AgentConfig
    ): List<ApiMessage>
}
