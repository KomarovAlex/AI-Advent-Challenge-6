package ru.koalexse.aichallenge.agent

import ru.koalexse.aichallenge.agent.context.strategy.ContextTruncationStrategy

/**
 * Расширение [Agent] для агентов, поддерживающих изменение конфигурации
 * и стратегии в рантайме.
 *
 * Единственная реализация — [SimpleLLMAgent].
 *
 * Разделение обосновано ISP: потребители, которым мутация не нужна
 * (тесты, headless-режим, read-only наблюдатели), работают с базовым [Agent]
 * и не получают методы, которые им недоступны семантически.
 *
 * Потребители с правом на мутацию (например, [ru.koalexse.aichallenge.ui.AgentChatViewModel])
 * явно объявляют зависимость на [ConfigurableAgent].
 */
interface ConfigurableAgent : Agent {

    /**
     * Обновляет конфигурацию агента.
     * Потокобезопасен: реализация защищена `synchronized`.
     */
    fun updateConfig(newConfig: AgentConfig)

    /**
     * Обновляет стратегию обрезки контекста.
     * Потокобезопасен: реализация защищена `synchronized`.
     *
     * Передача `null` отключает обрезку контекста.
     */
    fun updateTruncationStrategy(strategy: ContextTruncationStrategy?)
}
