# 📝 Рецепты

## Быстрый старт

### Инициализация

```kotlin
private val appModule by lazy {
    AppContainer.initialize(
        context = applicationContext,
        apiKey = BuildConfig.OPENAI_API_KEY,
        baseUrl = BuildConfig.OPENAI_URL,
        availableModels = BuildConfig.OPENAI_MODELS.split(",")
    )
}

// Стратегия по умолчанию — SUMMARY, профиль подключён автоматически
val viewModel = appModule.createAgentChatViewModel()

// С явным указанием стратегии
val viewModel = appModule.createAgentChatViewModel(
    initialStrategyType = ContextStrategyType.LAYERED_MEMORY
)
```

### Агент с LayeredMemory вручную

```kotlin
val strategy = LayeredMemoryStrategy(
    api = statsLLMApi,
    memoryStorage = JsonMemoryStorage(context),
    memoryModel = "gpt-4",
    keepRecentCount = 10,
    autoRefreshThreshold = 2
)
val agent: ConfigurableAgent = SimpleLLMAgent(
    api = statsLLMApi,
    initialConfig = agentConfig,
    agentContext = SimpleAgentContext(),
    truncationStrategy = strategy
)

val working  = (agent.truncationStrategy as? LayeredMemoryStrategy)?.getWorkingMemory()
val longTerm = (agent.truncationStrategy as? LayeredMemoryStrategy)?.getLongTermMemory()
```

### Агент с профилем вручную

```kotlin
val profileStorage = JsonProfileStorage(context)

val profilePromptProvider = ActiveProfileSystemPromptProvider {
    val selectedId = profileStorage.getSelectedId()
    profileStorage.getById(selectedId)
}

val agent: ConfigurableAgent = SimpleLLMAgent(
    api = statsLLMApi,
    initialConfig = agentConfig,
    agentContext = SimpleAgentContext(),
    truncationStrategy = strategy,
    profilePromptProvider = profilePromptProvider
)
```

### Агент через DSL (buildAgent)

```kotlin
val agent: ConfigurableAgent = buildAgent {
    withApi(statsLLMApi)
    model("gpt-4")
    temperature(0.7f)
    systemPrompt("You are a helpful assistant.")
    truncationStrategy(myStrategy)
}
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

---

## Типичные задачи

### Добавить новый Intent

1. Добавить case в `sealed class ChatIntent` (`ui/AgentChatViewModel.kt`)
2. Обработать в `handleIntent()`

### Добавить поле в настройки

1. `SettingsData` (`ui/state/ChatUiState.kt`)
2. `AgentConfig` (`agent/AgentModels.kt`) — если нужно влиять на агента
3. `Dialog.kt` — добавить UI-поле в `MultiFieldInputDialog`

### Добавить стратегию обрезки

1. Реализовать `ContextTruncationStrategy` в `agent/context/strategy/`
2. Переопределить `clear()` если стратегия хранит состояние
3. Добавить вариант в `ContextStrategyType` (`ui/state/ChatUiState.kt`)
4. Добавить создание в `AppModule.buildStrategy()`
5. Добавить `displayName()` в `Dialog.kt`
6. Добавить capability accessor в `AgentChatViewModel` при необходимости

> ⚠️ Не путать стратегию с Planning mode: `TaskStateMachineAgent` — отдельный режим,
> не реализует `ContextTruncationStrategy`, в `ContextStrategyType` не добавляется,
> в список стратегий диалога не попадает.

### Включить Planning mode из кода (программно)

```kotlin
// Пользователь нажимает Switch в диалоге настроек → onConfirm вызывается с новым SettingsData
handleIntent(ChatIntent.SaveSettings(
    currentSettingsData.copy(isPlanningMode = true)
))

// handleSettingsUpdate() обнаруживает !wasPlanningMode && nowPlanningMode:
// → isPlanningMode = true в InternalState
// → activeAgent = taskStateMachineAgent
// → taskStateMachineAgent.getTaskState()  ← загружаем сохранённое состояние
//
// Стратегия контекста (activeStrategy) НЕ меняется
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
layeredMemoryStrategy?.refreshWorkingMemory(agent.conversationHistory)
layeredMemoryStrategy?.refreshLongTermMemory(agent.conversationHistory)

// Branches — через Agent:
agent.getBranches()
agent.createCheckpoint()
agent.switchToBranch(branchId)

// Planning mode / Task State Machine:
taskStateMachineAgent?.getTaskState()
taskStateMachineAgent?.startTask(phaseInvariants)
taskStateMachineAgent?.advancePhase()
taskStateMachineAgent?.resetTask()
```

### Политика LONG_TERM: только добавление

```
Существующие записи в LONG_TERM:
  • name: Алексей
  • preferred language: Kotlin

Нажали 🧠 → LLM видит диалог "мой фреймворк — Compose":
  LLM возвращает: "framework: Compose"
  appendLongTerm → "framework" новый → добавляется
  LONG_TERM: { name: Алексей, preferred language: Kotlin, framework: Compose }

Нажали 🧠 снова, LLM вернул "name: Коля" ← галлюцинация:
  appendLongTerm → "name" уже есть → игнорируется ✅
```

### ClearSession vs ClearAllMemory

```
ClearSession (🧹 в overflow):
  agent.clearHistory() → strategy.clear() → memoryStorage.clearSession()
    WORKING    → очищен
    compressed → очищен
    LONG_TERM  → НЕ тронут

ClearAllMemory (ChatIntent.ClearAllMemory):
  layeredMemoryStrategy?.clearAllMemory() → memoryStorage.clearAll()
    WORKING, LONG_TERM, compressed → все очищены
```

### Planning mode: задача переживает ClearSession

```
Пользователь в Planning mode, задача активна (EXECUTION):
  → нажимает 🧹 ClearSession
  → история чата сбрасывается
  → task_state.json НЕ тронут
  → taskState в UI сохраняется
  → следующий запрос: LLM видит ## Task State блок и продолжает

Сбросить задачу явно:
  → overflow-меню → «Reset task» → ChatIntent.ResetTask
  → taskStateMachineAgent.resetTask() → архивирует → isActive=false
```

### Профиль и system-промпт

```
ProfileEditScreen → rawText → «Извлечь факты»
  → LLMSummaryProvider (FACTS_EXTRACTION_PROMPT)
  → Profile.facts = ["Имя: Алексей", "Локация: Москва", ...]
  → profileStorage.save(profile)

ProfileListScreen → выбор профиля → setSelectedId(id)

Следующий запрос:
  buildMessageList()
    → profilePromptProvider.getProfileBlock()
      → "## User Profile\n- Имя: Алексей\n- Локация: Москва"
    → добавляется первым блоком в system-промпт
```

---

## Тесты

```kotlin
@Test
fun `LONG_TERM: append does not overwrite existing keys`() = runTest {
    val storage = InMemoryMemoryStorage()
    storage.appendLongTerm(listOf(MemoryEntry("name", "Alexey", MemoryLayer.LONG_TERM)))

    storage.appendLongTerm(listOf(
        MemoryEntry("name", "Kolya", MemoryLayer.LONG_TERM),      // существующий — остаётся
        MemoryEntry("language", "Kotlin", MemoryLayer.LONG_TERM)  // новый — добавляется
    ))

    val result = storage.getLongTerm()
    assertEquals(2, result.size)
    assertEquals("Alexey", result.first { it.key == "name" }.value)
    assertEquals("Kotlin", result.first { it.key == "language" }.value)
}

@Test
fun `LONG_TERM: persists after clearSession`() = runTest {
    val storage = InMemoryMemoryStorage()
    storage.appendLongTerm(listOf(MemoryEntry("name", "Alexey", MemoryLayer.LONG_TERM)))

    val strategy = LayeredMemoryStrategy(api = mockApi, memoryStorage = storage, memoryModel = "gpt-4")
    val agent: Agent = SimpleLLMAgent(api = mockApi, initialConfig = config, truncationStrategy = strategy)
    agent.addToHistory(AgentMessage(Role.USER, "Hello"))

    agent.clearHistory()

    assertEquals(1, storage.getLongTerm().size)
    assertTrue(storage.getWorking().isEmpty())
}

@Test
fun `planning mode: task state persists across clearSession`() = runTest {
    val taskStorage = InMemoryTaskStateStorage()
    val tsm = TaskStateMachineAgent(
        innerAgent = SimpleLLMAgent(api = mockApi, initialConfig = config),
        api = mockApi,
        taskStateStorage = taskStorage,
        taskModel = "gpt-4"
    )

    tsm.startTask(emptyList())
    val stateBeforeClear = tsm.getTaskState()
    assertTrue(stateBeforeClear.isActive)

    tsm.clearHistory()  // ClearSession — только история, не задача

    val stateAfterClear = tsm.getTaskState()
    assertTrue(stateAfterClear.isActive)  // задача сохранилась ✅
    assertEquals(stateBeforeClear.taskId, stateAfterClear.taskId)
}

@Test
fun `profile: empty facts — getProfileBlock returns null`() = runTest {
    val provider = ActiveProfileSystemPromptProvider {
        Profile(id = "1", name = "Test", facts = emptyList())
    }
    assertNull(provider.getProfileBlock())
}

@Test
fun `configurable: updateTruncationStrategy swaps strategy at runtime`() = runTest {
    val agent: ConfigurableAgent = SimpleLLMAgent(api = mockApi, initialConfig = config)
    assertNull(agent.truncationStrategy)

    val strategy = SlidingWindowStrategy()
    agent.updateTruncationStrategy(strategy)

    assertSame(strategy, agent.truncationStrategy)
}
```
