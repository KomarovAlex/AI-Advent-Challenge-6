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
    val windowSize: Int = 10,              // кол-во хранимых сообщений
    private val tokenEstimator: TokenEstimator = TokenEstimators.default
) : ContextTruncationStrategy
```

- Не добавляет системных сообщений
- Минимальная сложность, максимальная скорость

---

## Стратегия 2 — Sticky Facts

```
История: [M1…M20], keepRecentCount=10

В LLM-запросе:
  [system: "Key facts: goal: X, language: Kotlin"]   ← facts блок
  [M11…M20]                                           ← последние 10 сообщений
  [userMessage]

UI:
  📌 Key facts bubble (всегда сверху)
  [M11…M20]
```

```kotlin
class StickyFactsStrategy(
    private val api: StatsLLMApi,           // для LLM-вызова обновления фактов
    private val factsStorage: FactsStorage, // персистенция (JsonFactsStorage)
    val keepRecentCount: Int = 10,
    private val factsModel: String,         // модель для извлечения фактов
    private val tokenEstimator: TokenEstimator = TokenEstimators.default
) : ContextTruncationStrategy

// Для Agent.getFacts() / refreshFacts() / loadFacts()
suspend fun getFacts(): List<Fact>
suspend fun refreshFacts(history: List<AgentMessage>): List<Fact>
suspend fun loadFacts(facts: List<Fact>)
suspend fun clearFacts()
```

### Fact

```kotlin
data class Fact(
    val key: String,
    val value: String,
    val updatedAt: Long = System.currentTimeMillis()
)
```

### Обновление фактов

- Запускается вручную кнопкой «✨» в тулбаре
- Блокирует ввод на время LLM-вызова (`isRefreshingFacts = true`)
- Ответ LLM парсится в `List<Fact>` (формат `key: value` по строкам)
- Факты персистируются в `facts.json` сразу после обновления

### FactsStorage

```kotlin
interface FactsStorage {
    suspend fun getFacts(): List<Fact>
    suspend fun replaceFacts(facts: List<Fact>)
    suspend fun clear()
}
// InMemoryFactsStorage — для тестов
// JsonFactsStorage     — персистенция (data/persistence/)
```

---

## Стратегия 3 — Branching

```
Начало: автоматически создаётся Branch 1 (пустая)

[Checkpoint нажат] → Branch 1 сохранена, создана Branch 2 (копия Branch 1)
Активна: Branch 2

Пользователь продолжает в Branch 2...
[Checkpoint нажат] → Branch 2 сохранена, создана Branch 3
Активна: Branch 3

Переключение на Branch 1:
  → история заменяется на историю Branch 1
  → Branch 3 сохранена перед переключением
```

```kotlin
class BranchingStrategy(
    private val branchStorage: BranchStorage,
    val windowSize: Int? = null,            // опциональный лимит сообщений
    private val tokenEstimator: TokenEstimator = TokenEstimators.default
) : ContextTruncationStrategy {
    suspend fun getBranches(): List<DialogBranch>
    suspend fun getActiveBranchId(): String?
    suspend fun ensureInitialized(): String          // создаёт Branch 1 если пусто
    suspend fun createCheckpoint(...): DialogBranch? // null если лимит 5 достигнут
    suspend fun switchToBranch(...): DialogBranch?
    suspend fun saveActiveBranch(...)
    suspend fun clearBranches()
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
// JsonBranchStorage     — персистенция (data/persistence/), branches.json
```

### Ограничения

- Максимум `BranchingStrategy.MAX_BRANCHES = 5` веток
- Кнопка Checkpoint становится неактивной при достижении лимита
- Имена генерируются автоматически: Branch 1, Branch 2, …

---

## Стратегия 4 — Summary (существующая)

```
История: [M1 … M15], keepRecentCount=5, summaryBlockSize=10

До:   [M1, M2, …, M10, M11, M12, M13, M14, M15]
После truncate():
  _context:        [M11, M12, M13, M14, M15]
  summaryStorage:  ConversationSummary(content="…", originalMessages=[M1…M10])

LLM-запрос:        [system: summary] + [M11…M15] + [новый вопрос]
UI:                [M1🗜️ … M10🗜️] + [M11…M15] + [новый вопрос]
```

> `originalMessages` → только UI. В LLM уходит только `content`.

---

## ContextTruncationStrategy

```kotlin
interface ContextTruncationStrategy {
    suspend fun truncate(
        messages: List<AgentMessage>,
        maxTokens: Int?,
        maxMessages: Int?
    ): List<AgentMessage>

    // По умолчанию emptyList(). Стратегии с компрессией/фактами переопределяют.
    suspend fun getAdditionalSystemMessages(): List<AgentMessage> = emptyList()
}
```

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

1. Пользователь открывает диалог настроек (кнопка ⚙️ в тулбаре)
2. Выбирает стратегию из выпадающего списка
3. `SaveSettings` → `ViewModel.handleSettingsUpdate()` → обновляет `activeStrategy` в `InternalState`
4. Кнопки тулбара обновляются в зависимости от `activeStrategy`:
   - `STICKY_FACTS` → показывает кнопку ✨ (Refresh Facts)
   - `BRANCHING`    → показывает кнопки 🔖 (Checkpoint) и 🌿 (Switch Branch)

> ⚠️ При смене стратегии агент **не пересоздаётся** — меняется только флаг UI.
> Фактическая смена стратегии требует рестарта через `AppModule.createAgentChatViewModelWith*()`.
> Это сознательный компромисс: для учебного проекта достаточно.
