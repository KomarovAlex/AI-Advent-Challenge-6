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
val agent = SimpleLLMAgent(
    api = statsLLMApi,
    initialConfig = agentConfig,
    agentContext = SimpleAgentContext(),
    truncationStrategy = strategy
)

// Чтение через capability
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

val agent = SimpleLLMAgent(
    api = statsLLMApi,
    initialConfig = agentConfig,
    agentContext = SimpleAgentContext(),
    truncationStrategy = strategy,          // любая стратегия или null
    profilePromptProvider = profilePromptProvider
)
// При каждом запросе агент динамически читает активный профиль.
// Смена профиля пользователем отражается в следующем запросе — без перезапуска агента.
```

Через `AppModule` профиль подключается автоматически — ручная сборка нужна только
вне `AppModule` (тесты, CLI-утилиты).

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
3. `Dialog.kt` — UI

### Добавить стратегию обрезки

1. Реализовать `ContextTruncationStrategy` в `agent/context/strategy/`
2. Переопределить `clear()` если стратегия хранит состояние
3. Добавить вариант в `ContextStrategyType` (`ui/state/ChatUiState.kt`)
4. Добавить создание в `AppModule.buildStrategy()`
5. Добавить `displayName()` в `Dialog.kt`
6. Добавить capability accessor в `AgentChatViewModel` при необходимости

### Получить доступ к данным стратегии из ViewModel

```kotlin
// Summaries:
summaryStrategy?.getSummaries()
summaryStrategy?.loadSummaries(list)

// Facts:
factsStrategy?.getFacts()
factsStrategy?.refreshFacts(agent.conversationHistory)

// Layered Memory — чтение:
layeredMemoryStrategy?.getWorkingMemory()
layeredMemoryStrategy?.getLongTermMemory()
layeredMemoryStrategy?.getCompressedMessages()

// WORKING (полная замена — задача могла смениться):
layeredMemoryStrategy?.refreshWorkingMemory(agent.conversationHistory)

// LONG_TERM (только добавление новых фактов — существующие не трогаются):
layeredMemoryStrategy?.refreshLongTermMemory(agent.conversationHistory)

// Branches — через Agent:
agent.getBranches()
agent.createCheckpoint()
agent.switchToBranch(branchId)
```

### Понять, что попало в каждый слой памяти

```
SHORT_TERM  → agent.conversationHistory              — последние keepRecentCount сообщений
WORKING     → layeredMemoryStrategy?.getWorkingMemory()   — текущая задача, шаги
LONG_TERM   → layeredMemoryStrategy?.getLongTermMemory()  — профиль, решения, знания
compressed  → layeredMemoryStrategy?.getCompressedMessages() — вытесненные из окна
```

В LLM-запросе:
```
[system: ## User Profile]   ← если facts активного профиля не пусты
[system: long-term]         ← если не пуст
[system: working]           ← если не пуст
[history: M(n-N+1)…Mn]
```

### Политика LONG_TERM: только добавление

```
Существующие записи в LONG_TERM:
  • name: Алексей
  • preferred language: Kotlin

Нажали 🧠 → LLM видит диалог "мой фреймворк — Compose":
  LLM возвращает: "framework: Compose"   (промпт: не повторяй существующее)
  appendLongTerm  → "framework" — новый ключ → добавляется

  LONG_TERM: { name: Алексей, preferred language: Kotlin, framework: Compose }

Нажали 🧠 снова, LLM вернул "name: Коля"  ← галлюцинация:
  appendLongTerm → "name" уже есть → игнорируется ✅

Удалить всё → только ClearAllMemory (явное действие пользователя)
```

### ClearSession vs ClearAllMemory

```
ClearSession (кнопка 🧹):
  agent.clearHistory() → strategy.clear() → memoryStorage.clearSession()
    WORKING    → очищен
    compressed → очищен
    LONG_TERM  → НЕ тронут ← persist между сессиями

ClearAllMemory (ChatIntent.ClearAllMemory):
  layeredMemoryStrategy?.clearAllMemory() → memoryStorage.clearAll()
    WORKING, LONG_TERM, compressed → все очищены
```

### Профиль и system-промпт

```
Пользователь открывает ProfileEditScreen → вводит rawText → нажимает «Извлечь факты»
  → ProfileEditViewModel → LLMSummaryProvider (FACTS_EXTRACTION_PROMPT)
  → Profile.facts = ["Имя: Алексей", "Локация: Москва", ...]
  → profileStorage.save(profile)

Пользователь выбирает профиль в ProfileListScreen → setSelectedId(id)

Следующий запрос к агенту:
  buildMessageList()
    → profilePromptProvider.getProfileBlock()
      → profileStorage.getSelectedId() → "550e8400-..."
      → profileStorage.getById("550e8400-...") → Profile(facts=[...])
      → "## User Profile\n- Имя: Алексей\n- Локация: Москва"
    → добавляется первым блоком в system-промпт
```

---

## Тесты

```kotlin
@Test
fun `LONG_TERM: append does not overwrite existing keys`() = runTest {
    val storage = InMemoryMemoryStorage()
    storage.appendLongTerm(listOf(
        MemoryEntry("name", "Alexey", MemoryLayer.LONG_TERM)
    ))

    // Пытаемся перезаписать существующий ключ
    storage.appendLongTerm(listOf(
        MemoryEntry("name", "Kolya", MemoryLayer.LONG_TERM),  // существующий — должен остаться
        MemoryEntry("language", "Kotlin", MemoryLayer.LONG_TERM)  // новый — должен добавиться
    ))

    val result = storage.getLongTerm()
    assertEquals(2, result.size)
    assertEquals("Alexey", result.first { it.key == "name" }.value)  // не перезаписан
    assertEquals("Kotlin", result.first { it.key == "language" }.value)
}

@Test
fun `LONG_TERM: persists after clearSession`() = runTest {
    val storage = InMemoryMemoryStorage()
    storage.appendLongTerm(listOf(MemoryEntry("name", "Alexey", MemoryLayer.LONG_TERM)))

    val strategy = LayeredMemoryStrategy(
        api = mockApi, memoryStorage = storage, memoryModel = "gpt-4"
    )
    val agent = SimpleLLMAgent(api = mockApi, initialConfig = config, truncationStrategy = strategy)
    agent.addToHistory(AgentMessage(Role.USER, "Hello"))

    agent.clearHistory()  // → clearSession → LONG_TERM не трогается

    assertEquals(1, storage.getLongTerm().size)
    assertEquals("Alexey", storage.getLongTerm().first().value)
    assertTrue(storage.getWorking().isEmpty())
}

@Test
fun `LONG_TERM: clearAll removes everything`() = runTest {
    val storage = InMemoryMemoryStorage()
    storage.appendLongTerm(listOf(MemoryEntry("name", "Alexey", MemoryLayer.LONG_TERM)))
    storage.replaceWorking(listOf(MemoryEntry("task", "write tests", MemoryLayer.WORKING)))

    storage.clearAll()

    assertTrue(storage.getLongTerm().isEmpty())
    assertTrue(storage.getWorking().isEmpty())
}

@Test
fun `WORKING: refreshWorkingMemory replaces fully`() = runTest {
    val storage = InMemoryMemoryStorage()
    storage.replaceWorking(listOf(MemoryEntry("old task", "done", MemoryLayer.WORKING)))

    val mockApi = MockStatsLLMApi("new task: write documentation\nstatus: in-progress")
    val strategy = LayeredMemoryStrategy(
        api = mockApi, memoryStorage = storage, memoryModel = "gpt-4"
    )

    strategy.refreshWorkingMemory(listOf(AgentMessage(Role.USER, "Now writing docs")))

    val working = storage.getWorking()
    assertFalse(working.any { it.key == "old task" })  // старое удалено
    assertTrue(working.any { it.key == "new task" })    // новое добавлено
}

@Test
fun `layered memory: getAdditionalSystemMessages returns both layers`() = runTest {
    val storage = InMemoryMemoryStorage()
    storage.replaceWorking(listOf(MemoryEntry("task", "write tests", MemoryLayer.WORKING)))
    storage.appendLongTerm(listOf(MemoryEntry("name", "Alexey", MemoryLayer.LONG_TERM)))

    val strategy = LayeredMemoryStrategy(
        api = mockApi, memoryStorage = storage, memoryModel = "gpt-4"
    )

    val systemMessages = strategy.getAdditionalSystemMessages()

    assertEquals(2, systemMessages.size)
    assertTrue(systemMessages[0].content.contains("Long-term memory"))
    assertTrue(systemMessages[1].content.contains("Working memory"))
}

@Test
fun `layered memory: empty layers skipped in system messages`() = runTest {
    val storage = InMemoryMemoryStorage()
    // только WORKING, LONG_TERM пуст
    storage.replaceWorking(listOf(MemoryEntry("task", "X", MemoryLayer.WORKING)))

    val strategy = LayeredMemoryStrategy(
        api = mockApi, memoryStorage = storage, memoryModel = "gpt-4"
    )

    val systemMessages = strategy.getAdditionalSystemMessages()

    assertEquals(1, systemMessages.size)
    assertTrue(systemMessages[0].content.contains("Working memory"))
}

@Test
fun `profile: empty facts — getProfileBlock returns null`() = runTest {
    val provider = ActiveProfileSystemPromptProvider {
        Profile(id = "1", name = "Test", facts = emptyList())
    }
    assertNull(provider.getProfileBlock())
}

@Test
fun `profile: facts present — block starts with header`() = runTest {
    val provider = ActiveProfileSystemPromptProvider {
        Profile(id = "1", name = "Test", facts = listOf("Имя: Алексей", "Локация: Москва"))
    }
    val block = provider.getProfileBlock()
    assertNotNull(block)
    assertTrue(block!!.startsWith("## User Profile"))
    assertTrue(block.contains("- Имя: Алексей"))
    assertTrue(block.contains("- Локация: Москва"))
}

@Test
fun `profile: null profile — getProfileBlock returns null`() = runTest {
    val provider = ActiveProfileSystemPromptProvider { null }
    assertNull(provider.getProfileBlock())
}
```
