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

---

## Стратегия 1 — Sliding Window

```
История: [M1, M2, …, M15], windowSize=10

После truncate():
  _context: [M6, M7, …, M15]   ← только последние 10
  Старые сообщения отброшены без компрессии
```

---

## Стратегия 2 — Sticky Facts

```
После truncate():
  _context (→ LLM):  [M11…M20]
  factsStorage:      facts=[goal: X, language: Kotlin]
                     compressed=[M1…M10]

В LLM-запросе:
  [system: "Key facts: goal: X, language: Kotlin"]
  [M11…M20]
```

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
После truncate():
  _context:        [M11…M15]
  summaryStorage:  ConversationSummary(content="…", originalMessages=[M1…M10])

LLM-запрос:  [system: summary] + [M11…M15]
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
| `WORKING` | Текущая задача, шаги, промежуточные результаты | LLM-вызов при вытеснении (авто) + кнопка 💼 | ✅ как `[system]` |
| `LONG_TERM` | Профиль, решения, устойчивые знания | Только явный запрос пользователя (кнопка 🧠) | ✅ как `[system]` |

### Политика обновления LONG_TERM — только добавление

**LONG_TERM никогда не перезаписывается.** Два уровня защиты:

| Уровень | Механизм |
|---------|----------|
| **Промпт** | LLM получает существующие записи «для справки» и инструкцию возвращать **только новые факты** с ключами, которых ещё нет. Экономия токенов, меньше галлюцинаций. |
| **Код** | `MemoryStorage.appendLongTerm()` фильтрует по `existingKeys` — даже если LLM вернул существующий ключ, он игнорируется. **Существующий ключ всегда побеждает.** |

```
Нажали 🧠 первый раз, в диалоге: "Меня зовут Алексей, пишу на Kotlin"
  LLM → "name: Алексей\npreferred language: Kotlin"
  appendLongTerm → добавлено (ключей не было)
  LONG_TERM: { name: Алексей, preferred language: Kotlin }

Нажали 🧠 второй раз, в диалоге: "Кстати, мой фреймворк — Compose"
  LLM → "framework: Compose"           ← промпт сказал «не повторяй существующее»
  appendLongTerm → "framework" — новый ключ → добавлено
  LONG_TERM: { name: Алексей, preferred language: Kotlin, framework: Compose }

Нажали 🧠 третий раз, LLM вдруг вернул "name: Коля"  ← галлюцинация
  appendLongTerm → "name" уже есть в existingKeys → игнорируется
  LONG_TERM: без изменений ✅

Удаление — только через ClearAllMemory (явное действие пользователя)
```

### Что уходит в LLM-запрос

```
[system: "Long-term memory: ..."]    ← из getAdditionalSystemMessages() (если не пуст)
[system: "Working memory: ..."]      ← из getAdditionalSystemMessages() (если не пуст)
[M(n-N+1) … Mn]                      ← SHORT_TERM из _context
```

### Что видно в UI

```
🧠 Long-term memory bubble           ← tertiaryContainer
💼 Working memory bubble             ← primaryContainer
[M1🗜️ … Mk🗜️]                       ← вытесненные сообщения (только UI)
[Mk+1 … Mn]                          ← recent messages
```

### Логика truncate()

```
messages = [M1…M20], keepRecentCount=10

recentMessages = [M11…M20]  → в _context и LLM
oldMessages    = [M1…M10]   → в compressedMessages (только UI)

1. memoryStorage.setCompressedMessages(already + oldMessages)
2. if (oldMessages.size >= autoRefreshThreshold):
       refreshWorkingMemory(oldMessages)   ← авто, ошибка проглатывается
3. return recentMessages
```

> LONG_TERM **не обновляется автоматически** — только по явному запросу.

### Обновление слоёв — два сценария

| Слой | Сценарий | Вызов | Политика |
|------|----------|-------|----------|
| WORKING | **Авто** (в `truncate`) | `refreshWorkingMemory(oldMessages)` | Полная замена — задача могла смениться |
| WORKING | **Ручной** (кнопка 💼) | `refreshWorkingMemory(agent.conversationHistory)` | Полная замена |
| LONG_TERM | **Ручной** (кнопка 🧠) | `refreshLongTermMemory(agent.conversationHistory)` | Только добавление новых ключей |

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
    // WORKING: LLM мёрджит → replaceWorking (полная замена)

    suspend fun refreshLongTermMemory(history: List<AgentMessage>): List<MemoryEntry>
    // LONG_TERM: LLM возвращает только новое → appendLongTerm (только добавление)

    // Полная очистка (включая LONG_TERM):
    suspend fun clearAllMemory()
}
```

### MemoryStorage — интерфейс

```kotlin
interface MemoryStorage {
    // Working — полная замена разрешена (задача меняется)
    suspend fun getWorking(): List<MemoryEntry>
    suspend fun replaceWorking(entries: List<MemoryEntry>)

    // Long-term — только добавление через appendLongTerm
    suspend fun getLongTerm(): List<MemoryEntry>
    suspend fun appendLongTerm(entries: List<MemoryEntry>)  // только новые ключи
    suspend fun replaceLongTerm(entries: List<MemoryEntry>) // только для clearAll

    // Compressed (только UI)
    suspend fun getCompressedMessages(): List<AgentMessage>
    suspend fun setCompressedMessages(messages: List<AgentMessage>)

    suspend fun clearSession()  // WORKING + compressed; LONG_TERM — нет
    suspend fun clearAll()      // всё включая LONG_TERM
}
// InMemoryMemoryStorage  — для тестов
// JsonMemoryStorage      — три отдельных файла (data/persistence/)
```

### Persistence — три отдельных файла

| Файл | Слой | Очищается при `clearSession`? |
|------|------|-------------------------------|
| `memory_working.json` | WORKING | ✅ да |
| `memory_long_term.json` | LONG_TERM | ❌ нет (persist между сессиями, только `clearAll`) |
| `memory_compressed.json` | compressed UI | ✅ да |

### Capability pattern в ViewModel

```kotlin
private val layeredMemoryStrategy: LayeredMemoryStrategy?
    get() = agent.truncationStrategy as? LayeredMemoryStrategy

// Загрузка при старте (JsonMemoryStorage восстанавливает из файлов лениво):
val savedWorking    = layeredMemoryStrategy?.getWorkingMemory()      ?: emptyList()
val savedLongTerm   = layeredMemoryStrategy?.getLongTermMemory()     ?: emptyList()
val savedCompressed = layeredMemoryStrategy?.getCompressedMessages() ?: emptyList()

// Ручной refresh 💼 (WORKING — полная замена):
val updated = layeredMemoryStrategy?.refreshWorkingMemory(agent.conversationHistory)

// Ручной refresh 🧠 (LONG_TERM — только добавление новых фактов):
val updated = layeredMemoryStrategy?.refreshLongTermMemory(agent.conversationHistory)

// После каждого ответа (Completed) — синхронизация:
val newWorking    = layeredMemoryStrategy?.getWorkingMemory()      ?: emptyList()
val newLongTerm   = layeredMemoryStrategy?.getLongTermMemory()     ?: emptyList()
val newCompressed = layeredMemoryStrategy?.getCompressedMessages() ?: emptyList()
```

### ClearSession: LONG_TERM намеренно сохраняется

```kotlin
// agent.clearHistory() → strategy.clear() → memoryStorage.clearSession():
//   WORKING    → очищен
//   compressed → очищен
//   LONG_TERM  → НЕ тронут

// После clearSession ViewModel читает longTerm из storage:
val longTermAfterClear = layeredMemoryStrategy?.getLongTermMemory() ?: emptyList()
_internalState.update {
    it.copy(
        workingMemory            = emptyList(),
        longTermMemory           = longTermAfterClear,  // ← persist!
        memoryCompressedMessages = emptyList()
    )
}
```

---

## Сравнение всех стратегий

| | Sliding Window | Sticky Facts | Summary | Branching | Layered Memory |
|---|---|---|---|---|---|
| Что в LLM вместо старых сообщений | ничего | key-value факты | текст summary | ничего | working + long-term |
| Автообновление | — | ✅ (facts) | ✅ | — | ✅ (только WORKING) |
| Ручное обновление | — | ✅ кнопка ✨ | — | — | ✅ кнопки 💼 и 🧠 |
| Политика обновления | — | мёрдж | накопление | snapshot | WORKING: замена; LONG_TERM: **только добавление** |
| Persistence | — | `facts.json` | `summaries.json` | `branches.json` | 3 файла |
| Persist между сессиями | — | ✅ | ✅ | ✅ | ✅ LONG_TERM всегда; WORKING сбрасывается |
| LLM-вызовы помимо основного | — | 1 | 1 | — | до 2 |

---

## TruncationUtils

```kotlin
object TruncationUtils {
    fun truncateByTokens(messages, maxTokens, estimator): List<AgentMessage>
}
```

Все стратегии используют `TruncationUtils.truncateByTokens` — не дублировать.

---

## Переключение стратегий в UI

1. Пользователь открывает настройки (кнопка ⚙️) → выбирает стратегию
2. `SaveSettings` → `ViewModel.handleSettingsUpdate()` → `applyStrategyChange()`
3. `agent.updateTruncationStrategy(factory(newStrategyType))`
4. ViewModel читает начальное состояние через capability accessor новой стратегии
5. Кнопки тулбара:
   - `STICKY_FACTS`   → ✨ Refresh Facts
   - `BRANCHING`      → 🔖 Checkpoint, 🌿 Switch Branch
   - `LAYERED_MEMORY` → 💼 Working Memory, 🧠 Long-Term Memory
