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

// Без компрессии
val viewModel = appModule.createAgentChatViewModel()

// С компрессией (SummaryStorage инкапсулирован внутри агента)
val viewModel = appModule.createAgentChatViewModelWithCompression(
    keepRecentCount = 10,
    summaryBlockSize = 10,
    useLLMForSummary = true
)
```

### Агент вручную

```kotlin
// SummaryStorage — деталь реализации, снаружи не нужен
val agent = SimpleLLMAgent(
    api = statsLLMApi,
    initialConfig = agentConfig,
    agentContext = SimpleAgentContext(),
    truncationStrategy = SummaryTruncationStrategy(
        summaryProvider = LLMSummaryProvider(statsLLMApi, model = "gpt-4"),
        summaryStorage = JsonSummaryStorage(context),  // остаётся внутри агента
        keepRecentCount = 10,
        summaryBlockSize = 10
    )
)

// Доступ к summaries — только через агент
val summaries = agent.getSummaries()
agent.loadSummaries(savedSummaries)
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
    truncationStrategy(myStrategy)
}
```

---

## Типичные задачи

### Добавить новый Intent в чат
1. Добавить case в `sealed class ChatIntent` (`ui/AgentChatViewModel.kt`)
2. Обработать в `handleIntent()`

### Добавить поле в настройки
1. `SettingsData` (`ui/state/ChatUiState.kt`)
2. `AgentConfig` (`agent/AgentModels.kt`)
3. `Dialog.kt` — UI

### Добавить стратегию обрезки
1. Реализовать `ContextTruncationStrategy` (`agent/context/strategy/`)
2. Подключить в `AppModule` или `buildAgent {}`

### Изменить формат сохранения истории
1. Обновить модели в `ChatHistoryModels.kt`
2. Обновить маппер в `ChatHistoryMapper.kt`
3. При необходимости — миграция в `JsonChatHistoryRepository`

---

## Тесты

```kotlin
// Агент тестируется без Android — не нужен Context, эмулятор
@Test
fun `compressed messages not sent to LLM`() = runTest {
    val storage = InMemorySummaryStorage()
    val agent = SimpleLLMAgent(
        api = mockApi,
        initialConfig = config,
        truncationStrategy = SummaryTruncationStrategy(
            summaryProvider = MockSummaryProvider("Summary"),
            summaryStorage = storage,
            keepRecentCount = 5,
            summaryBlockSize = 10
        )
    )
    repeat(15) { agent.addToHistory(AgentMessage(Role.USER, "Message $it")) }

    assertEquals(5, agent.conversationHistory.size)

    // Summaries через агент — не через storage напрямую
    val summaries = agent.getSummaries()
    assertEquals(1, summaries.size)
    assertEquals(10, summaries.first().originalMessages.size)
}
```
