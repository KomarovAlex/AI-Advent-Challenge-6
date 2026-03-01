package ru.koalexse.aichallenge.agent

import ru.koalexse.aichallenge.agent.context.strategy.ContextTruncationStrategy

/**
 * Фабрика для создания агентов
 *
 * Инкапсулирует логику создания и конфигурирования агентов.
 * Может использоваться как простая замена DI в небольших проектах.
 */
object AgentFactory {

    /**
     * Создаёт агента с существующим [StatsLLMApi]
     *
     * @param statsApi API с поддержкой статистики
     * @param config конфигурация агента
     * @param truncationStrategy стратегия обрезки контекста (null = без обрезки)
     * @return настроенный агент
     */
    fun createAgentWithStats(
        statsApi: StatsLLMApi,
        config: AgentConfig,
        truncationStrategy: ContextTruncationStrategy? = null
    ): Agent {
        return SimpleLLMAgent(
            api = statsApi,
            initialConfig = config,
            truncationStrategy = truncationStrategy
        )
    }
}

/**
 * Builder для более гибкого создания агента
 */
class AgentBuilder {
    private var api: StatsLLMApi? = null

    private var model: String = "gpt-4"
    private var temperature: Float? = null
    private var maxTokens: Long? = null
    private var systemPrompt: String? = null
    private var stopSequences: List<String>? = null
    private var keepHistory: Boolean = true
    private var maxHistorySize: Int? = null
    private var maxContextTokens: Int? = null
    private var truncationStrategy: ContextTruncationStrategy? = null

    /**
     * Устанавливает готовый API
     */
    fun withApi(api: StatsLLMApi): AgentBuilder {
        this.api = api
        return this
    }

    fun model(model: String): AgentBuilder {
        this.model = model
        return this
    }

    fun temperature(temperature: Float): AgentBuilder {
        this.temperature = temperature
        return this
    }

    fun maxTokens(maxTokens: Long): AgentBuilder {
        this.maxTokens = maxTokens
        return this
    }

    fun systemPrompt(prompt: String): AgentBuilder {
        this.systemPrompt = prompt
        return this
    }

    fun stopSequences(sequences: List<String>): AgentBuilder {
        this.stopSequences = sequences
        return this
    }

    fun keepHistory(keep: Boolean): AgentBuilder {
        this.keepHistory = keep
        return this
    }

    fun maxHistorySize(size: Int): AgentBuilder {
        this.maxHistorySize = size
        return this
    }

    fun maxContextTokens(tokens: Int): AgentBuilder {
        this.maxContextTokens = tokens
        return this
    }

    fun truncationStrategy(strategy: ContextTruncationStrategy): AgentBuilder {
        this.truncationStrategy = strategy
        return this
    }

    fun build(): Agent {
        val statsApi = api ?: throw IllegalStateException("StatsLLMApi must be provided via withApi()")

        val config = AgentConfig(
            defaultModel = model,
            defaultTemperature = temperature,
            defaultMaxTokens = maxTokens,
            defaultSystemPrompt = systemPrompt,
            defaultStopSequences = stopSequences,
            keepConversationHistory = keepHistory,
            maxHistorySize = maxHistorySize,
            maxContextTokens = maxContextTokens
        )

        return SimpleLLMAgent(
            api = statsApi,
            initialConfig = config,
            truncationStrategy = truncationStrategy
        )
    }
}

/**
 * DSL-функция для создания агента
 *
 * ```kotlin
 * val agent = buildAgent {
 *     withApi(statsApi)
 *     model("gpt-4")
 *     temperature(0.7f)
 *     systemPrompt("You are a helpful assistant.")
 *     truncationStrategy(myStrategy)
 * }
 * ```
 */
fun buildAgent(block: AgentBuilder.() -> Unit): Agent {
    return AgentBuilder().apply(block).build()
}
