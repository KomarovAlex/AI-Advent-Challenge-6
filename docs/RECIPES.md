# 📝 Рецепты

## Быстрый старт

### Инициализация

```kotlin
// MainActivity.kt
private val appModule by lazy {
    AppContainer.initialize(
        context = applicationContext,
        apiKey = BuildConfig.OPENAI_API_KEY,
        baseUrl = BuildConfig.OPENAI_URL,
        availableModels = BuildConfig.OPENAI_MODELS.split(",")
    )
}

// Без компрессии — стратегия по умолчанию SUMMARY
val viewModel = appModule.createAgentChatViewModel()

// С явным указанием стратегии
val viewModel = appModule.createAgentChatViewModel(
    initialStrategyType = ContextStrategyType.STICKY_FACTS
)

// С кастомными параметрами Summary-стратегии
val viewModel = appModule.createAgentChatViewModelWithCompression(
    keepRecentCount = 10,
    summaryBlockSize = 10,
    useLLMForSummary = true
)
```

### Агент вручную

```kotlin
val strategy = SummaryTruncationStrategy(
    summaryProvider = LLMSummaryProvider(statsLLMApi, model = "gpt-4"),
    summaryStorage = JsonSummaryStorage(context),
    keepRecentCount = 10,
    summaryBlockSize = 10
)
val agent = SimpleLLMAgent(
    api = statsLLMApi,
    initialConfig = agentConfig,
    agentContext = SimpleAgentContext(),
    truncationStrategy = strategy
)

// Доступ к summaries — через capability, не через Agent
val summaries = (agent.truncationStrategy as? SummaryTruncationStrategy)?.getSummaries()
```

### Отправка сообщения

```kotlin
agent.send("Привет!").collect { event ->
    when (event) {
        is AgentStreamEvent.ContentDelta -> print(event.text)
        is AgentStreamEvent.Completed   -> println("\nTokens: ${event.tokenStats}")
        is AgentStreamEvent.Error       -> println("Error: ${event.exception}")
    }
}
```

### Builder DSL

```kotlin
val agent = buildAgent {
    withApi(statsApi)
    model("gpt-4")
    temperature(0.7f)
    systemPrompt("You are a helpful assistant.")
    maxContextTokens(8000)   // лимит контекста истории
    maxTokens(4096L)          // лимит ответа (Long)
    truncationStrategy(SlidingWindowStrategy(windowSize = 20))
}
```

---

## Типичные задачи

### Добавить новый Intent в чат
1. Добавить case в `sealed class ChatIntent` (`ui/AgentChatViewModel.kt`)
2. Обработать в `handleIntent()`

### Добавить поле в настройки
1. `SettingsData` (`ui/state/ChatUiState.kt`)
2. `AgentConfig` (`agent/AgentModels.kt`) — если нужно влиять на агента
3. `Dialog.kt` — UI

### Добавить стратегию обрезки
1. Реализовать `ContextTruncationStrategy` в `agent/context/strategy/`
2. Переопределить `clear()` если стратегия хранит состояние
3. Добавить вариант в `ContextStrategyType` (`ui/state/ChatUiState.kt`)
4. Добавить создание в `AppModule.buildStrategy()`
5. Если нужен доступ из ViewModel — добавить capability accessor:

```kotlin
// В AgentChatViewModel:
private val myStrategy: MyCustomStrategy?
    get() = agent.truncationStrategy as? MyCustomStrategy
```

### Изменить формат сохранения истории
1. Обновить модели в `ChatHistoryModels.kt`
2. Обновить маппер в `ChatHistoryMapper.kt`
3. При необходимости — добавить миграцию в `JsonChatHistoryRepository`

### Получить доступ к данным стратегии из ViewModel

```kotlin
// Summaries:
summaryStrategy?.getSummaries()
summaryStrategy?.loadSummaries(list)

// Facts:
factsStrategy?.getFacts()
factsStrategy?.refreshFacts(agent.conversationHistory)
factsStrategy?.loadFacts(list)

// Branches — через Agent (требуют синхронизации _context):
agent.getBranches()
agent.getActiveBranchId()
agent.createCheckpoint()
agent.switchToBranch(branchId)
```

---

## Тесты

```kotlin
// Агент тестируется без Android — не нужен Context, эмулятор
@Test
fun `compressed messages not sent to LLM`() = runTest {
    val storage = InMemorySummaryStorage()
    val strategy = SummaryTruncationStrategy(
        summaryProvider = MockSummaryProvider("Summary"),
        summaryStorage = storage,
        keepRecentCount = 5,
        summaryBlockSize = 10
    )
    val agent = SimpleLLMAgent(
        api = mockApi,
        initialConfig = config,
        truncationStrategy = strategy
    )
    repeat(15) { agent.addToHistory(AgentMessage(Role.USER, "Message $it")) }

    assertEquals(5, agent.conversationHistory.size)

    // Summaries — через capability, не через Agent
    val summaries = (agent.truncationStrategy as? SummaryTruncationStrategy)?.getSummaries()
    assertEquals(1, summaries?.size)
    assertEquals(10, summaries?.first()?.originalMessages?.size)
}

@Test
fun `clearHistory delegates to strategy`() = runTest {
    val storage = InMemorySummaryStorage()
    storage.addSummary(ConversationSummary("text", emptyList()))

    val agent = SimpleLLMAgent(
        api = mockApi,
        initialConfig = config,
        truncationStrategy = SummaryTruncationStrategy(
            summaryProvider = MockSummaryProvider(""),
            summaryStorage = storage
        )
    )
    agent.clearHistory()

    assertTrue(storage.isEmpty())
}

@Test
fun `AgentConfig maxContextTokens vs defaultMaxTokens are distinct`() {
    val config = AgentConfig(
        defaultModel = "gpt-4",
        defaultMaxTokens = 4096L,    // ограничение ОТВЕТА (Long)
        maxContextTokens = 8000      // ограничение КОНТЕКСТА (Int)
    )
    assertNotEquals(config.defaultMaxTokens.toInt(), config.maxContextTokens)
}
```
