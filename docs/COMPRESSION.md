# 🗜️ Стратегии управления контекстом

## Обзор

Все стратегии реализуют `ContextTruncationStrategy` и подключаются к `SimpleLLMAgent`.
Переключение — через `ContextStrategyType` в настройках UI.

| Стратегия | Класс | Файл данных | `getAdditionalSystemMessages` |
|-----------|-------|-------------|-------------------------------|
| Sliding Window | `SlidingWindowStrategy` | — | `emptyList()` |
| Sticky Facts | `StickyFactsStrategy` | `facts.json` | факты как `[system]` |
| Branching | `BranchingStrategy` | `branches.json` | `emptyList()` |
| Summary | `SummaryTruncationStrategy` | `summaries.json` | summary как `[system]` |

---

## ContextTruncationStrategy — контракт

```kotlin
interface ContextTruncationStrategy {
    suspend fun truncate(
        messages: List<AgentMessage>,
        maxTokens: Int?,       // из AgentConfig.maxContextTokens
        maxMessages: Int?      // из AgentConfig.maxHistorySize
    ): List<AgentMessage>

    // По умолчанию emptyList(). Summary и Facts переопределяют.
    suspend fun getAdditionalSystemMessages(): List<AgentMessage> = emptyList()

    // По умолчанию no-op. Стратегии с состоянием переопределяют.
    // Вызывается агентом в clearHistory() — агент не знает конкретный тип.
    suspend fun clear() {}
}
```

### Добавить новую стратегию

1. Реализовать `ContextTruncationStrategy` в `agent/context/strategy/`
2. Переопределить `clear()` если стратегия имеет состояние
3. Добавить вариант в `ContextStrategyType` и `AppModule.buildStrategy()`
4. Если нужен доступ из ViewModel — добавить capability accessor в `AgentChatViewModel`

```kotlin
// Пример capability accessor в ViewModel:
private val myStrategy: MyCustomStrategy?
    get() = agent.truncationStrategy as? MyCustomStrategy
```

---

## Стратегия 1 — Sliding Window

```
История: [M1, M2, …, M15], windowSize=10

До:   [M1, M2, …, M15]
После truncate():
  _context: [M6, M7, …, M15]   ← только последние 10
  Старые сообщения отброшены без компрессии
```

```kotlin
class SlidingWindowStrategy(
    val windowSize: Int = 10,
    private val tokenEstimator: TokenEstimator = TokenEstimators.default
) : ContextTruncationStrategy
// clear() — no-op (нет состояния)
```

---

## Стратегия 2 — Sticky Facts

```
История: [M1…M20], keepRecentCount=10

В LLM-запросе:
  [system: "Key facts: goal: X, language: Kotlin"]   ← facts блок
  [M11…M20]                                           ← последние 10 сообщений

UI:
  📌 Key facts bubble (всегда сверху)
  [M11…M20]
```

```kotlin
class StickyFactsStrategy(
    private val api: StatsLLMApi,
    private val factsStorage: FactsStorage,
    val keepRecentCount: Int = 10,
    private val factsModel: String,
    private val tokenEstimator: TokenEstimator = TokenEstimators.default
) : ContextTruncationStrategy {

    // clear() → factsStorage.clear()

    // Доступ через capability (ViewModel):
    suspend fun getFacts(): List<Fact>
    suspend fun refreshFacts(history: List<AgentMessage>): List<Fact>
    suspend fun loadFacts(facts: List<Fact>)
    suspend fun clearFacts()
}
```

### Использование из ViewModel (capability pattern)

```kotlin
// В AgentChatViewModel:
private val factsStrategy: StickyFactsStrategy?
    get() = agent.truncationStrategy as? StickyFactsStrategy

// Загрузка при старте:
val savedFacts = factsStrategy?.getFacts() ?: emptyList()

// Обновление по кнопке:
val updated = factsStrategy?.refreshFacts(agent.conversationHistory) ?: emptyList()
```

### Fact

```kotlin
data class Fact(val key: String, val value: String, val updatedAt: Long)
```

### FactsStorage

```kotlin
interface FactsStorage {
    suspend fun getFacts(): List<Fact>
    suspend fun replaceFacts(facts: List<Fact>)
    suspend fun clear()
}
// InMemoryFactsStorage — для тестов
// JsonFactsStorage (data/persistence/) — персистенция, facts.json
```

---

## Стратегия 3 — Branching

```
Начало: автоматически создаётся Branch 1 (пустая)

[Checkpoint] → Branch 1 сохранена, создана Branch 2 (копия)
Активна: Branch 2

[Checkpoint] → Branch 2 сохранена, создана Branch 3
Активна: Branch 3

Переключение на Branch 1:
  → Branch 3 сохранена
  → история заменяется на историю Branch 1
  → _context.replaceHistory(branch1.messages)   ← агент делает это сам
```

```kotlin
class BranchingStrategy(
    private val branchStorage: BranchStorage,
    val windowSize: Int? = null,
    private val tokenEstimator: TokenEstimator = TokenEstimators.default
) : ContextTruncationStrategy {

    // clear() → branchStorage.clear()

    // Вызываются через Agent (требуют синхронизации _context):
    suspend fun ensureInitialized(): String
    suspend fun createCheckpoint(currentHistory, currentSummaries): DialogBranch?
    suspend fun switchToBranch(branchId, currentHistory, currentSummaries): DialogBranch?
    suspend fun saveActiveBranch(currentHistory, currentSummaries)
    suspend fun getBranches(): List<DialogBranch>
    suspend fun getActiveBranchId(): String?
    suspend fun clearBranches()
}
```

### Почему ветки — в Agent, а не через capability

`switchToBranch` и `initBranches` требуют `_context.replaceHistory()` — это зона агента.
Summaries/Facts — чистый I/O без изменения `_context`, поэтому через capability.

### saveCurrentBranchIfActive — устранение дублирования (#8)

До рефакторинга `createCheckpoint` и `switchToBranch` содержали одинаковый блок
«найти активную ветку, сохранить с новой историей». Теперь вынесен в приватный метод:

```kotlin
private suspend fun saveCurrentBranchIfActive(
    branches: List<DialogBranch>,
    currentHistory: List<AgentMessage>,
    currentSummaries: List<ConversationSummary>
) {
    val activeId = branchStorage.getActiveBranchId() ?: return
    val activeBranch = branches.find { it.id == activeId } ?: return
    branchStorage.saveBranch(activeBranch.copy(messages = currentHistory, summaries = currentSummaries))
}
```

### DialogBranch

```kotlin
data class DialogBranch(
    val id: String,
    val name: String,                         // "Branch 1", "Branch 2", …
    val messages: List<AgentMessage>,
    val summaries: List<ConversationSummary>, // если применялась компрессия
    val createdAt: Long
)
```

### BranchStorage

```kotlin
interface BranchStorage {
    suspend fun getBranches(): List<DialogBranch>
    suspend fun getActiveBranchId(): String?
    suspend fun saveBranch(branch: DialogBranch)
    suspend fun setActiveBranch(branchId: String)
    suspend fun clear()
}
// InMemoryBranchStorage — для тестов
// JsonBranchStorage (data/persistence/) — персистенция, branches.json
```

### Ограничения

- Максимум `BranchingStrategy.MAX_BRANCHES = 5` веток
- Кнопка Checkpoint становится неактивной при достижении лимита

---

## Стратегия 4 — Summary

```
История: [M1 … M15], keepRecentCount=5, summaryBlockSize=10

После truncate():
  _context:        [M11, M12, M13, M14, M15]
  summaryStorage:  ConversationSummary(content="…", originalMessages=[M1…M10])

LLM-запрос:  [system: summary] + [M11…M15]
UI:          [M1🗜️ … M10🗜️] + [M11…M15]
```

> `originalMessages` → только UI. В LLM уходит только `content`.

```kotlin
class SummaryTruncationStrategy(
    private val summaryProvider: SummaryProvider,
    private val summaryStorage: SummaryStorage,
    private val keepRecentCount: Int = 10,
    private val summaryBlockSize: Int = 10,
    private val tokenEstimator: TokenEstimator = TokenEstimators.default
) : ContextTruncationStrategy {

    // clear() → summaryStorage.clear()

    // Доступ через capability (ViewModel):
    suspend fun getSummaries(): List<ConversationSummary>
    suspend fun loadSummaries(summaries: List<ConversationSummary>)
    suspend fun clearSummaries()
}
```

### Лимиты после сжатия (#5 — исправлен)

После создания summary `maxTokens`/`maxMessages` применяются к `recentMessages`:

```kotlin
if (oldMessages.size >= summaryBlockSize) {
    summaryStorage.addSummary(ConversationSummary(...))
    var result = recentMessages
    // ← лимиты применяются и здесь, а не только в else-ветке
    if (maxMessages != null && result.size > maxMessages) result = result.takeLast(maxMessages)
    if (maxTokens != null) result = TruncationUtils.truncateByTokens(result, maxTokens, estimator)
    return result
}
```

### Использование из ViewModel (capability pattern)

```kotlin
private val summaryStrategy: SummaryTruncationStrategy?
    get() = agent.truncationStrategy as? SummaryTruncationStrategy

// Загрузка при старте:
summaryStrategy?.loadSummaries(savedSummaries)

// Чтение для persistence:
val summaries = summaryStrategy?.getSummaries() ?: emptyList()
```

---

## AgentConfig: maxContextTokens vs defaultMaxTokens

| Поле | Тип | Семантика | Куда передаётся |
|------|-----|-----------|-----------------|
| `defaultMaxTokens` | `Long?` | макс. токенов в **ответе** LLM | `ChatRequest.max_tokens` |
| `maxContextTokens` | `Int?` | макс. токенов в **контексте** истории | `strategy.truncate(maxTokens=...)` |
| `maxHistorySize` | `Int?` | макс. сообщений в контексте | `strategy.truncate(maxMessages=...)` |

---

## TruncationUtils

```kotlin
object TruncationUtils {
    fun truncateByTokens(
        messages: List<AgentMessage>,
        maxTokens: Int,
        estimator: TokenEstimator
    ): List<AgentMessage>
}
```

Все стратегии используют `TruncationUtils.truncateByTokens` — не дублировать.

---

## Переключение стратегий в UI

1. Пользователь открывает настройки (кнопка ⚙️)
2. Выбирает стратегию из списка
3. `SaveSettings` → `ViewModel.handleSettingsUpdate()` → `applyStrategyChange()`
4. `agent.updateTruncationStrategy(factory(newStrategyType))` — история в `_context` не трогается
5. ViewModel читает начальное состояние через capability accessor новой стратегии
6. Кнопки тулбара обновляются по `activeStrategy`:
   - `STICKY_FACTS` → кнопка ✨ (Refresh Facts)
   - `BRANCHING`    → кнопки 🔖 (Checkpoint) и 🌿 (Switch Branch)
