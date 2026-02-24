package ru.koalexse.aichallenge.di

import android.content.Context
import ru.koalexse.aichallenge.agent.Agent
import ru.koalexse.aichallenge.agent.AgentConfig
import ru.koalexse.aichallenge.agent.AgentFactory
import ru.koalexse.aichallenge.agent.buildAgent
import ru.koalexse.aichallenge.data.LLMApi
import ru.koalexse.aichallenge.data.OpenAIApi
import ru.koalexse.aichallenge.data.StatsLLMApi
import ru.koalexse.aichallenge.data.StatsTrackingLLMApi
import ru.koalexse.aichallenge.data.persistence.ChatHistoryRepository
import ru.koalexse.aichallenge.data.persistence.JsonChatHistoryRepository
import ru.koalexse.aichallenge.ui.AgentChatViewModel

/**
 * Простой модуль зависимостей без использования DI-фреймворков
 * 
 * В реальном приложении рекомендуется использовать Hilt/Koin/Dagger,
 * но для демонстрации подходит и ручное создание зависимостей.
 * 
 * Использование:
 * ```
 * val appModule = AppModule(
 *     context = applicationContext,
 *     apiKey = "your-api-key",
 *     baseUrl = "https://api.example.com/v1/chat/completions",
 *     availableModels = listOf("gpt-4", "gpt-3.5-turbo")
 * )
 * 
 * // Вариант 1: Использование старого ChatRepository
 * val viewModel = appModule.createChatViewModel()
 * 
 * // Вариант 2: Использование нового AgentChatViewModel
 * val agentViewModel = appModule.createAgentChatViewModel()
 * ```
 */
class AppModule(
    private val context: Context,
    private val apiKey: String,
    private val baseUrl: String,
    private val availableModels: List<String>,
    private val defaultModel: String = availableModels.firstOrNull() ?: "gpt-4"
) {

    // ==================== Lazy инициализация зависимостей ====================

    /**
     * Базовый LLM API
     */
    val llmApi: LLMApi by lazy {
        OpenAIApi(apiKey, baseUrl)
    }

    /**
     * LLM API с поддержкой статистики
     */
    val statsLLMApi: StatsLLMApi by lazy {
        StatsTrackingLLMApi(llmApi)
    }

    /**
     * Конфигурация агента по умолчанию
     */
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

    /**
     * Агент для работы с LLM
     */
    val agent: Agent by lazy {
        AgentFactory.createAgentWithStats(statsLLMApi, agentConfig)
    }
    
    /**
     * Репозиторий для хранения истории чата
     */
    val chatHistoryRepository: ChatHistoryRepository by lazy {
        JsonChatHistoryRepository(context)
    }

    // ==================== Фабричные методы ====================

    /**
     * Создаёт AgentChatViewModel (новая версия с Agent)
     * 
     * История чата автоматически сохраняется между запусками приложения.
     */
    fun createAgentChatViewModel(): AgentChatViewModel {
        return AgentChatViewModel(
            agent = agent,
            availableModels = availableModels,
            chatHistoryRepository = chatHistoryRepository
        )
    }

    /**
     * Создаёт агента через builder
     */
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
            maxHistorySize(scope.maxHistorySize)
        }
    }

    /**
     * Scope для builder DSL
     */
    class AgentBuilderScope {
        var model: String? = null
        var temperature: Float? = null
        var maxTokens: Long? = null
        var systemPrompt: String? = null
        var stopSequences: List<String>? = null
        var keepHistory: Boolean = true
        var maxHistorySize: Int = 100
    }
}

/**
 * Глобальный контейнер зависимостей (Singleton)
 * 
 * Использование:
 * ```
 * // В Application.onCreate():
 * AppContainer.initialize(context, apiKey, baseUrl, models)
 * 
 * // В Activity/Fragment:
 * val viewModel = AppContainer.module.createAgentChatViewModel()
 * ```
 */
object AppContainer {
    private var _module: AppModule? = null

    val module: AppModule
        get() = _module ?: throw IllegalStateException(
            "AppContainer not initialized. Call initialize() first."
        )

    /**
     * Инициализирует контейнер
     * 
     * @param context контекст приложения (рекомендуется applicationContext)
     * @param apiKey API ключ
     * @param baseUrl базовый URL API
     * @param availableModels список доступных моделей
     * @param defaultModel модель по умолчанию
     */
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

    /**
     * Проверяет, инициализирован ли контейнер
     */
    val isInitialized: Boolean
        get() = _module != null

    /**
     * Сбрасывает контейнер (для тестов)
     */
    fun reset() {
        _module = null
    }
}
