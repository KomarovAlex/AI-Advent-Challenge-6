# 🗜️ Стратегии управления контекстом

## Обзор

Все стратегии реализуют `ContextTruncationStrategy` и подключаются к `SimpleLLMAgent`.
Переключение — через `ContextStrategyType` в настройках UI.

| Стратегия | Класс | Файл(ы) данных | `getAdditionalSystemMessages` |
|-----------|-------|----------------|-------------------------------|
| Sliding Window | `SlidingWindowStrategy` | — | `emptyList()` |
| Sticky Facts | `StickyFactsStrategy` | `facts.json` | факты как `[system]` |
| Branching | `BranchingStrategy` | `branches.json` | `emptyList()` |
| Summary | `SummaryTruncationStrategy` | `summaries.json` | summary как `[system]` |
| Layered Memory | `LayeredMemoryStrategy` | `memory_working.json`, `memory_long_term.json`, `memory_compressed.json` | working + long-term как два `[system]` |

---

## ContextTruncationStrategy — контракт

```kotlin
interface ContextTruncationStrategy {
    suspend fun truncate(
        messages: List<AgentMessage>,
        maxTokens: Int?,       // из AgentConfig.maxContextTokens
        maxMessages: Int?      // из AgentConfig.maxHistorySize
    ): List<AgentMessage>

    // По умолчанию emptyList(). Summary, Facts и LayeredMemory переопределяют.
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
История: [M1…M20], keepRecentCount=10, autoRefreshThreshold=2

После truncate():
  _context (→ LLM):  [M11…M20]                           ← только recent
  factsStorage:      facts=[goal: X, language: Kotlin]   ← обновлены автоматически
                     compressed=[M1…M10]                 ← вытесненные (только UI)

В LLM-запросе:
  [system: "Key facts: goal: X, language: Kotlin"]   ← из getAdditionalSystemMessages()
  [M11…M20]                                           ← recent из _context

UI:
  📌 Key facts bubble             ← факты (всегда сверху)
  [M1🗜️ … M10🗜️]                 ← compressed messages (только UI, не идут в LLM)
  [M11…M20]                       ← recent messages
```

> `compressedMessages` — только UI.
> В LLM уходят **только факты** (как system-сообщение) + recent-сообщения.

### Сигнатура класса

```kotlin
class StickyFactsStrategy(
    private val api: StatsLLMApi,
    private val factsStorage: FactsStorage,
    val keepRecentCount: Int = 10,
    private val factsModel: String,
    private val tokenEstimator: TokenEstimator = TokenEstimators.default,
    val autoRefreshThreshold: Int = 2
) : ContextTruncationStrategy {
    // clear() → factsStorage.clear()
    suspend fun getFacts(): List<Fact>
    suspend fun getCompressedMessages(): List<AgentMessage>
    suspend fun refreshFacts(history: List<AgentMessage>): List<Fact>
}
```

---

## Стратегия 3 — Branching

```
Начало: автоматически создаётся Branch 1 (пустая)

[Checkpoint] → Branch 1 сохранена, создана Branch 2 (копия)
Активна: Branch 2

Переключение на Branch 1:
  → Branch 2 сохранена
  → история заменяется на историю Branch 1
  → _context.replaceHistory(branch1.messages)
```

```kotlin
class BranchingStrategy(
    private val branchStorage: BranchStorage,
    val windowSize: Int? = null,
    private val tokenEstimator: TokenEstimator = TokenEstimators.default
) : ContextTruncationStrategy {
    // clear() → branchStorage.clear()
    suspend fun ensureInitialized(): String
    suspend fun createCheckpoint(currentHistory, currentSummaries): DialogBranch?
    suspend fun switchToBranch(branchId, currentHistory, currentSummaries): DialogBranch?
    suspend fun getBranches(): List<DialogBranch>
    suspend fun getActiveBranchId(): String?
}
```

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

```kotlin
class SummaryTruncationStrategy(
    private val summaryProvider: SummaryProvider,
    private val summaryStorage: SummaryStorage,
    private val keepRecentCount: Int = 10,
    private val summaryBlockSize: Int = 10,
    private val tokenEstimator: TokenEstimator = TokenEstimators.default
) : ContextTruncationStrategy {
    // clear() → summaryStorage.clear()
    suspend fun getSummaries(): List<ConversationSummary>
    suspend fun loadSummaries(summaries: List<ConversationSummary>)
}
```

---

## Стратегия 5 — Layered Memory

### Три слоя памяти

| Слой | Что хранит | Триггер обновления | В LLM |
|------|-----------|-------------------|-------|
| `SHORT_TERM` | Последние `keepRecentCount` сообщений | Авто (скользящее окно в `truncate`) | ✅ как история `_context` |
| `WORKING` | Текущая задача, шаги, промежуточные результаты | LLM-вызов при вытеснении сообщений (авто) + кнопка 💼 | ✅ как `[system]` |
| `LONG_TERM` | Профиль, решения, устойчивые знания | Только явный запрос пользователя (кнопка 🧠) | ✅ как `[system]` |

### Что уходит в LLM-запрос

```
[system: "Long-term memory: ..."]    ← из getAdditionalSystemMessages()
[system: "Working memory: ..."]      ← из getAdditionalSystemMessages()
[M(n-N+1) … Mn]                      ← SHORT_TERM из _context (recent-окно)
```

Пустые слои пропускаются — если LONG_TERM пуст, его system-сообщение не добавляется.

### Что видно в UI

```
🧠 Long-term memory bubble           ← долговременная память (фиолетовый фон)
💼 Working memory bubble             ← рабочая память (синий фон)
[M1🗜️ … Mk🗜️]                       ← вытесненные сообщения (только UI)
[Mk+1 … Mn]                          ← recent messages (SHORT_TERM)
```

### Логика truncate()

```
messages = [M1…M20], keepRecentCount=10

recentMessages = [M11…M20]  → в _context и LLM
oldMessages    = [M1…M10]   → в compressedMessages (только UI)

1. memoryStorage.setCompressedMessages(already + oldMessages)
   → накапливаем для UI

2. if (oldMessages.size >= autoRefreshThreshold):
       refreshWorkingMemory(oldMessages)   ← LLM-вызов (авто, ошибка проглатывается)

3. return recentMessages → в _context агента
```

> **LONG_TERM не обновляется автоматически** — только по явному запросу пользователя.
> Это сделано намеренно: профиль и решения не должны меняться без ведома пользователя.

### Обновление памяти — два сценария

| Слой | Сценарий | Вызов | `history` |
|------|----------|-------|-----------|
| WORKING | **Авто** (внутри `truncate`) | `refreshWorkingMemory(oldMessages)` | вытесняемый блок |
| WORKING | **Ручной** (кнопка 💼) | `refreshWorkingMemory(agent.conversationHistory)` | текущая active-история |
| LONG_TERM | **Ручной** (кнопка 🧠) | `refreshLongTermMemory(agent.conversationHistory)` | текущая active-история |

### Промпты

**WORKING** — фокус на текущей задаче:
- цель и подцели задачи
- шаги и их статус (pending/done/in-progress)
- промежуточные результаты и переменные
- открытые вопросы и блокеры

**LONG_TERM** — фокус на профиле пользователя:
- имя, роль, уровень экспертизы
- стабильные предпочтения (язык, инструменты, фреймворки)
- важные решения
- долгосрочные цели и проекты

LLM отвечает в формате `key: value` (одна запись на строку) или `NO_ENTRIES`.

### Сигнатура класса

```kotlin
class LayeredMemoryStrategy(
    private val api: StatsLLMApi,
    private val memoryStorage: MemoryStorage,
    val keepRecentCount: Int = 10,
    private val memoryModel: String,
    private val tokenEstimator: TokenEstimator = TokenEstimators.default,
    val autoRefreshThreshold: Int = 2
) : ContextTruncationStrategy {

    // clear() → memoryStorage.clearSession()
    //   очищает WORKING и compressed, LONG_TERM намеренно не трогает

    // Capability (чтение):
    suspend fun getWorkingMemory(): List<MemoryEntry>
    suspend fun getLongTermMemory(): List<MemoryEntry>
    suspend fun getCompressedMessages(): List<AgentMessage>

    // Capability (обновление):
    suspend fun refreshWorkingMemory(history: List<AgentMessage>): List<MemoryEntry>
    suspend fun refreshLongTermMemory(history: List<AgentMessage>): List<MemoryEntry>

    // Полная очистка (включая LONG_TERM):
    suspend fun clearAllMemory()
}
```

### MemoryStorage — интерфейс

```kotlin
interface MemoryStorage {
    suspend fun getWorking(): List<MemoryEntry>
    suspend fun replaceWorking(entries: List<MemoryEntry>)
    suspend fun getLongTerm(): List<MemoryEntry>
    suspend fun replaceLongTerm(entries: List<MemoryEntry>)
    suspend fun getCompressedMessages(): List<AgentMessage>
    suspend fun setCompressedMessages(messages: List<AgentMessage>)
    suspend fun clearSession()   // очищает WORKING + compressed, LONG_TERM — нет
    suspend fun clearAll()       // очищает всё включая LONG_TERM
}

// InMemoryMemoryStorage  — для тестов
// JsonMemoryStorage      — три отдельных файла (data/persistence/)
```

### Модели данных

```kotlin
enum class MemoryLayer { SHORT_TERM, WORKING, LONG_TERM }

data class MemoryEntry(
    val key: String,
    val value: String,
    val layer: MemoryLayer,
    val updatedAt: Long = System.currentTimeMillis()
)
```

### Persistence — три отдельных файла

| Файл | Слой | Очищается при `clearSession`? |
|------|------|-------------------------------|
| `memory_working.json` | WORKING | ✅ да |
| `memory_long_term.json` | LONG_TERM | ❌ нет (persist между сессиями) |
| `memory_compressed.json` | compressed UI | ✅ да |

### Capability pattern в ViewModel

```kotlin
private val layeredMemoryStrategy: LayeredMemoryStrategy?
    get() = agent.truncationStrategy as? LayeredMemoryStrategy

// Загрузка при старте:
val savedWorking    = layeredMemoryStrategy?.getWorkingMemory()    ?: emptyList()
val savedLongTerm   = layeredMemoryStrategy?.getLongTermMemory()   ?: emptyList()
val savedCompressed = layeredMemoryStrategy?.getCompressedMessages() ?: emptyList()

// Ручной refresh по кнопке 💼:
val updated = layeredMemoryStrategy?.refreshWorkingMemory(agent.conversationHistory)

// Ручной refresh по кнопке 🧠:
val updated = layeredMemoryStrategy?.refreshLongTermMemory(agent.conversationHistory)

// После каждого ответа (Completed) — синхронизация:
val newWorking   = layeredMemoryStrategy?.getWorkingMemory()    ?: emptyList()
val newLongTerm  = layeredMemoryStrategy?.getLongTermMemory()   ?: emptyList()
val newCompressed = layeredMemoryStrategy?.getCompressedMessages() ?: emptyList()
```

### ClearSession: LONG_TERM намеренно сохраняется

```kotlin
// ViewModel.clearSession():
agent.clearHistory()   // вызывает strategy.clear() → memoryStorage.clearSession()
//   ↳ WORKING очищен
//   ↳ compressed очищен
//   ↳ LONG_TERM — НЕ тронут

val longTermAfterClear = layeredMemoryStrategy?.getLongTermMemory() ?: emptyList()
_internalState.update {
    it.copy(
        workingMemory            = emptyList(),
        longTermMemory           = longTermAfterClear,  // ← persist!
        memoryCompressedMessages = emptyList()
    )
}
```

### Настройка в AppModule

```kotlin
ContextStrategyType.LAYERED_MEMORY -> LayeredMemoryStrategy(
    api = statsLLMApi,
    memoryStorage = JsonMemoryStorage(context),
    memoryModel = defaultModel,
    // keepRecentCount = 10  (по умолчанию) — размер SHORT_TERM окна
    // autoRefreshThreshold = 2  (по умолчанию) — триггер авторефреша WORKING
)
```

---

## Сравнение всех стратегий

| | Sliding Window | Sticky Facts | Summary | Branching | Layered Memory |
|---|---|---|---|---|---|
| Что в LLM вместо старых сообщений | ничего (отброшены) | key-value факты | текст summary | ничего (другая ветка) | working + long-term |
| Автообновление | — | ✅ (WORKING-аналог) | ✅ | — | ✅ (только WORKING) |
| Ручное обновление | — | ✅ кнопка ✨ | — | — | ✅ кнопки 💼 и 🧠 |
| Persistence | — | `facts.json` | `summaries.json` | `branches.json` | 3 файла |
| Persist между сессиями | — | ✅ | ✅ | ✅ | ✅ (LONG_TERM всегда, WORKING сбрасывается) |
| LLM-вызовы помимо основного | — | 1 (facts) | 1 (summary) | — | до 2 (working + long-term) |

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
2. Выбирает стратегию из списка (включая «Layered Memory 🧠»)
3. `SaveSettings` → `ViewModel.handleSettingsUpdate()` → `applyStrategyChange()`
4. `agent.updateTruncationStrategy(factory(newStrategyType))` — история в `_context` не трогается
5. ViewModel читает начальное состояние через capability accessor новой стратегии
6. Кнопки тулбара обновляются по `activeStrategy`:
   - `STICKY_FACTS`    → кнопка ✨ (Refresh Facts)
   - `BRANCHING`       → кнопки 🔖 (Checkpoint) и 🌿 (Switch Branch)
   - `LAYERED_MEMORY`  → кнопки 💼 (Working Memory) и 🧠 (Long-Term Memory)
