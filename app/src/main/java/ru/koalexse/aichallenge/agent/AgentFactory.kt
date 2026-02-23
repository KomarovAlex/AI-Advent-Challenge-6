package ru.koalexse.aichallenge.agent

import ru.koalexse.aichallenge.data.OpenAIApi
import ru.koalexse.aichallenge.data.StatsLLMApi
import ru.koalexse.aichallenge.data.StatsTrackingLLMApi

/**
 * Фабрика для создания агентов
 * 
 * Инкапсулирует логику создания и конфигурирования агентов.
 * Может использоваться как простая замена DI в небольших проектах.
 */
object AgentFactory {

    /**
     * Создаёт агента с существующим StatsLLMApi
     * 
     * @param statsApi API с поддержкой статистики
     * @param config конфигурация агента
     * @return настроенный агент
     */
    fun createAgentWithStats(
        statsApi: StatsLLMApi,
        config: AgentConfig
    ): Agent {
        return SimpleLLMAgent(statsApi, config)
    }
}

/**
 * Builder для более гибкого создания агента
 */
class AgentBuilder {
    private var api: StatsLLMApi? = null
    private var apiKey: String? = null
    private var baseUrl: String = "https://api.openai.com/v1/chat/completions"

    private var model: String = "gpt-4"
    private var temperature: Float? = null
    private var maxTokens: Long? = null
    private var systemPrompt: String? = null
    private var stopSequences: List<String>? = null
    private var keepHistory: Boolean = true
    private var maxHistorySize: Int? = null

    /**
     * Устанавливает готовый API
     */
    fun withApi(api: StatsLLMApi): AgentBuilder {
        this.api = api
        return this
    }

    /**
     * Устанавливает модель по умолчанию
     */
    fun model(model: String): AgentBuilder {
        this.model = model
        return this
    }

    /**
     * Устанавливает температуру по умолчанию
     */
    fun temperature(temperature: Float): AgentBuilder {
        this.temperature = temperature
        return this
    }

    /**
     * Устанавливает максимальное количество токенов
     */
    fun maxTokens(maxTokens: Long): AgentBuilder {
        this.maxTokens = maxTokens
        return this
    }

    /**
     * Устанавливает системный промпт
     */
    fun systemPrompt(prompt: String): AgentBuilder {
        this.systemPrompt = prompt
        return this
    }

    /**
     * Устанавливает стоп-последовательности
     */
    fun stopSequences(sequences: List<String>): AgentBuilder {
        this.stopSequences = sequences
        return this
    }

    /**
     * Включает/выключает сохранение истории
     */
    fun keepHistory(keep: Boolean): AgentBuilder {
        this.keepHistory = keep
        return this
    }

    /**
     * Устанавливает максимальный размер истории
     */
    fun maxHistorySize(size: Int): AgentBuilder {
        this.maxHistorySize = size
        return this
    }

    /**
     * Создаёт агента с заданными параметрами
     */
    fun build(): Agent {
        val statsApi = api ?: run {
            val key =
                apiKey ?: throw IllegalStateException("API key or StatsLLMApi must be provided")
            val llmApi = OpenAIApi(key, baseUrl)
            StatsTrackingLLMApi(llmApi)
        }

        val config = AgentConfig(
            defaultModel = model,
            defaultTemperature = temperature,
            defaultMaxTokens = maxTokens,
            defaultSystemPrompt = systemPrompt,
            defaultStopSequences = stopSequences,
            keepConversationHistory = keepHistory,
            maxHistorySize = maxHistorySize
        )

        return SimpleLLMAgent(statsApi, config)
    }
}

/**
 * DSL-функция для создания агента
 * 
 * Пример использования:
 * ```
 * val agent = buildAgent {
 *     withOpenAI("sk-xxx", "https://api.example.com/v1/chat/completions")
 *     model("gpt-4")
 *     temperature(0.7f)
 *     systemPrompt("You are a helpful assistant.")
 * }
 * ```
 */
fun buildAgent(block: AgentBuilder.() -> Unit): Agent {
    return AgentBuilder().apply(block).build()
}
