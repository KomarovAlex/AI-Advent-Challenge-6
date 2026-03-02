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
    initialStrategyType = ContextStrategyType.LAYERED_MEMORY
)

// С кастомными параметрами Summary-стратегии
val viewModel = appModule.createAgentChatViewModelWithCompression(
    keepRecentCount = 10,
    summaryBlockSize = 10,
    useLLMForSummary = true
)
```

### Агент с LayeredMemory вручную

```kotlin
val strategy = LayeredMemoryStrategy(
    api = statsLLMApi,
    memoryStorage = JsonMemoryStorage(context),
    memoryModel = "gpt-4",
    keepRecentCount = 10,          // размер SHORT_TERM окна
    autoRefreshThreshold = 2       // триггер авторефреша WORKING
)
val agent = SimpleLLMAgent(
    api = statsLLMApi,
    initialConfig = agentConfig,
    agentContext = SimpleAgentContext(),
    truncationStrategy = strategy
)

// Доступ к слоям — через capability, не через Agent
val working  = (agent.truncationStrategy as? LayeredMemoryStrategy)?.getWorkingMemory()
val longTerm = (agent.truncationStrategy as? LayeredMemoryStrategy)?.getLongTermMemory()
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
    maxContextTokens(8000)
    maxTokens(4096L)
    truncationStrategy(LayeredMemoryStrategy(
        api = statsApi,
        memoryStorage = InMemoryMemoryStorage(),
        memoryModel = "gpt-4"
    ))
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
5. Добавить `displayName()` в `Dialog.kt`
6. Если нужен доступ из ViewModel — добавить capability accessor:

```kotlin
// В AgentChatViewModel:
private val myStrategy: MyCustomStrategy?
    get() = agent.truncationStrategy as? MyCustomStrategy
```

### Получить доступ к данным стратегии из ViewModel

```kotlin
// Summaries:
summaryStrategy?.getSummaries()
summaryStrategy?.loadSummaries(list)

// Facts:
factsStrategy?.getFacts()
factsStrategy?.refreshFacts(agent.conversationHistory)

// Layered Memory:
layeredMemoryStrategy?.getWorkingMemory()
layeredMemoryStrategy?.getLongTermMemory()
layeredMemoryStrategy?.getCompressedMessages()
layeredMemoryStrategy?.refreshWorkingMemory(agent.conversationHistory)   // авто или ручной
layeredMemoryStrategy?.refreshLongTermMemory(agent.conversationHistory)  // только ручной

// Branches — через Agent (требуют синхронизации _context):
agent.getBranches()
agent.getActiveBranchId()
agent.createCheckpoint()
agent.switchToBranch(branchId)
```

### Понять, что попало в каждый слой памяти

Для LayeredMemoryStrategy:

```
SHORT_TERM  → agent.conversationHistory           — последние keepRecentCount сообщений
WORKING     → layeredMemoryStrategy?.getWorkingMemory()  — что извлёк LLM из диалога
LONG_TERM   → layeredMemoryStrategy?.getLongTermMemory() — что пользователь сохранил явно
compressed  → layeredMemoryStrategy?.getCompressedMessages() — вытесненные из окна
```

В LLM-запросе:
```
[system: long-term]   ← getLongTermMemory() → если не пуст
[system: working]     ← getWorkingMemory()  → если не пуст
[history: M(n-N+1)…Mn] ← agent.conversationHistory
```

### ClearSession vs ClearAllMemory

```
ClearSession  (кнопка 🧹) → agent.clearHistory()
                            → strategy.clear()
                            → memoryStorage.clearSession()
                               ↳ WORKING очищен
                               ↳ compressed очищен
                               ↳ LONG_TERM — НЕ тронут (persist!)

ClearAllMemory (ChatIntent.ClearAllMemory) → layeredMemoryStrategy?.clearAllMemory()
                            → memoryStorage.clearAll()
                               ↳ WORKING, LONG_TERM, compressed — все очищены
```

### Изменить формат сохранения истории
1. Обновить модели в `ChatHistoryModels.kt`
2. Обновить маппер в `ChatHistoryMapper.kt`
3. При необходимости — добавить миграцию в `JsonChatHistoryRepository`

---

## Тесты

```kotlin
// Агент тестируется без Android — не нужен Context, эмулятор
@Test
fun `layered memory: working auto-refreshes on truncate`() = runTest {
    val storage = InMemoryMemoryStorage()
    val mockApi = MockStatsLLMApi("current task: write tests\nstatus: in-progress")
    val strategy = LayeredMemoryStrategy(
        api = mockApi,
        memoryStorage = storage,
        memoryModel = "gpt-4",
        keepRecentCount = 5,
        autoRefreshThreshold = 2
    )
    val agent = SimpleLLMAgent(
        api = mockApi,
        initialConfig = config,
        truncationStrategy = strategy
    )

    // Добавляем 7 сообщений — 2 вытесняются (>= autoRefreshThreshold)
    repeat(7) { agent.addToHistory(AgentMessage(Role.USER, "Message $it")) }

    // SHORT_TERM: только последние 5
    assertEquals(5, agent.conversationHistory.size)

    // WORKING: LLM вызвался для вытесненных 2 сообщений
    val working = (agent.truncationStrategy as? LayeredMemoryStrategy)?.getWorkingMemory()
    assertTrue(working?.isNotEmpty() == true)
}

@Test
fun `layered memory: long-term persists after clearSession`() = runTest {
    val storage = InMemoryMemoryStorage()
    storage.replaceLongTerm(listOf(MemoryEntry("name", "Alexey", MemoryLayer.LONG_TERM)))

    val strategy = LayeredMemoryStrategy(
        api = mockApi,
        memoryStorage = storage,
        memoryModel = "gpt-4"
    )
    val agent = SimpleLLMAgent(api = mockApi, initialConfig = config, truncationStrategy = strategy)
    agent.addToHistory(AgentMessage(Role.USER, "Hello"))

    agent.clearHistory()  // → strategy.clear() → clearSession()

    // LONG_TERM должен сохраниться
    assertEquals(1, storage.getLongTerm().size)
    assertEquals("Alexey", storage.getLongTerm().first().value)
    // WORKING должен очиститься
    assertTrue(storage.getWorking().isEmpty())
}

@Test
fun `layered memory: getAdditionalSystemMessages returns both layers`() = runTest {
    val storage = InMemoryMemoryStorage()
    storage.replaceWorking(listOf(MemoryEntry("task", "write tests", MemoryLayer.WORKING)))
    storage.replaceLongTerm(listOf(MemoryEntry("name", "Alexey", MemoryLayer.LONG_TERM)))

    val strategy = LayeredMemoryStrategy(
        api = mockApi, memoryStorage = storage, memoryModel = "gpt-4"
    )

    val systemMessages = strategy.getAdditionalSystemMessages()

    assertEquals(2, systemMessages.size)
    assertTrue(systemMessages[0].content.contains("Long-term memory"))
    assertTrue(systemMessages[1].content.contains("Working memory"))
}

@Test
fun `summary: compressed messages not sent to LLM`() = runTest {
    val storage = InMemorySummaryStorage()
    val strategy = SummaryTruncationStrategy(
        summaryProvider = MockSummaryProvider("Summary"),
        summaryStorage = storage,
        keepRecentCount = 5,
        summaryBlockSize = 10
    )
    val agent = SimpleLLMAgent(
        api = mockApi, initialConfig = config, truncationStrategy = strategy
    )
    repeat(15) { agent.addToHistory(AgentMessage(Role.USER, "Message $it")) }

    assertEquals(5, agent.conversationHistory.size)

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
```
