package ru.koalexse.aichallenge.di

import android.content.Context
import ru.koalexse.aichallenge.agent.Agent
import ru.koalexse.aichallenge.agent.AgentConfig
import ru.koalexse.aichallenge.agent.AgentFactory
import ru.koalexse.aichallenge.agent.SimpleLLMAgent
import ru.koalexse.aichallenge.agent.StatsLLMApi
import ru.koalexse.aichallenge.agent.buildAgent
import ru.koalexse.aichallenge.agent.context.SimpleAgentContext
import ru.koalexse.aichallenge.agent.context.strategy.SummaryTruncationStrategy
import ru.koalexse.aichallenge.agent.context.summary.JsonSummaryStorage
import ru.koalexse.aichallenge.agent.context.summary.LLMSummaryProvider
import ru.koalexse.aichallenge.agent.context.summary.SimpleSummaryProvider
import ru.koalexse.aichallenge.agent.context.summary.SummaryProvider
import ru.koalexse.aichallenge.data.LLMApi
import ru.koalexse.aichallenge.data.OpenAIApi
import ru.koalexse.aichallenge.data.StatsTrackingLLMApi
import ru.koalexse.aichallenge.data.persistence.ChatHistoryRepository
import ru.koalexse.aichallenge.data.persistence.JsonChatHistoryRepository
import ru.koalexse.aichallenge.ui.AgentChatViewModel

/**
 * Простой модуль зависимостей без использования DI-фреймворков.
 *
 * Граф зависимостей:
 *   UI → Agent → domain
 *   UI → data  → domain
 *   data реализует agent.StatsLLMApi  ✅
 *   ViewModel не знает о SummaryStorage — только об Agent ✅
 */
class AppModule(
    private val context: Context,
    private val apiKey: String,
    private val baseUrl: String,
    private val availableModels: List<String>,
    private val defaultModel: String = availableModels.firstOrNull() ?: "gpt-4"
) {

    val llmApi: LLMApi by lazy {
        OpenAIApi(apiKey, baseUrl)
    }

    /** Реализует [StatsLLMApi] из agent/ — инверсия зависимости. */
    val statsLLMApi: StatsLLMApi by lazy {
        StatsTrackingLLMApi(llmApi)
    }

    val agentConfig: AgentConfig by lazy {
        AgentConfig(
            defaultModel = defaultModel,
            defaultTemperature = 0.7f,
            defaultMaxTokens = 4096,
            defaultSystemPrompt = null,
            defaultStopSequences = listOf("===КОНЕЦ===", "-end-"),
            keepConversationHistory = true,
        )
    }

    val agent: Agent by lazy {
        AgentFactory.createAgentWithStats(statsLLMApi, agentConfig)
    }

    val chatHistoryRepository: ChatHistoryRepository by lazy {
        JsonChatHistoryRepository(context)
    }

    // ==================== Фабричные методы ====================

    fun createAgentChatViewModel(): AgentChatViewModel {
        return AgentChatViewModel(
            agent = agent,
            availableModels = availableModels,
            chatHistoryRepository = chatHistoryRepository
        )
    }

    /**
     * Создаёт ViewModel с агентом, у которого включена компрессия истории.
     * SummaryStorage инкапсулирован внутри агента — ViewModel о нём не знает.
     */
    fun createAgentChatViewModelWithCompression(
        keepRecentCount: Int = 10,
        summaryBlockSize: Int = 10,
        useLLMForSummary: Boolean = true,
        summaryModel: String? = null
    ): AgentChatViewModel {
        val summaryProvider: SummaryProvider = if (useLLMForSummary) {
            LLMSummaryProvider(api = statsLLMApi, model = summaryModel ?: defaultModel)
        } else {
            SimpleSummaryProvider()
        }

        val truncationStrategy = SummaryTruncationStrategy(
            summaryProvider = summaryProvider,
            summaryStorage = JsonSummaryStorage(context),
            keepRecentCount = keepRecentCount,
            summaryBlockSize = summaryBlockSize
        )

        val agentWithCompression = SimpleLLMAgent(
            api = statsLLMApi,
            initialConfig = agentConfig,
            agentContext = SimpleAgentContext(),
            truncationStrategy = truncationStrategy
        )

        return AgentChatViewModel(
            agent = agentWithCompression,
            availableModels = availableModels,
            chatHistoryRepository = chatHistoryRepository
        )
    }

    fun createAgentWithBuilder(block: AgentBuilderScope.() -> Unit): Agent {
        val scope = AgentBuilderScope()
        scope.block()
        return buildAgent {
            withApi(statsLLMApi)
            model(scope.model ?: defaultModel)
            scope.temperature?.let { temperature(it) }
            scope.maxTokens?.let { maxTokens(it) }
            scope.systemPrompt?.let { systemPrompt(it) }
            scope.stopSequences?.let { stopSequences(it) }
            keepHistory(scope.keepHistory)
            scope.maxHistorySize?.let { maxHistorySize(it) }
        }
    }

    class AgentBuilderScope {
        var model: String? = null
        var temperature: Float? = null
        var maxTokens: Long? = null
        var systemPrompt: String? = null
        var stopSequences: List<String>? = null
        var keepHistory: Boolean = true
        var maxHistorySize: Int? = null
    }
}

object AppContainer {
    private var _module: AppModule? = null

    val module: AppModule
        get() = _module ?: throw IllegalStateException(
            "AppContainer not initialized. Call initialize() first."
        )

    fun initialize(
        context: Context,
        apiKey: String,
        baseUrl: String,
        availableModels: List<String>,
        defaultModel: String = availableModels.firstOrNull() ?: "gpt-4"
    ): AppModule {
        _module = AppModule(context.applicationContext, apiKey, baseUrl, availableModels, defaultModel)
        return module
    }

    val isInitialized: Boolean get() = _module != null

    fun reset() { _module = null }
}
