# 🗜️ Стратегии управления контекстом

## Обзор

Все стратегии 1–5 реализуют `ContextTruncationStrategy` и подключаются к `SimpleLLMAgent`.
**Planning mode** (Task State Machine) — самостоятельный режим работы, не стратегия:
включается через Switch «Planning mode 🤖» в диалоге настроек, не через список стратегий.

| Стратегия | Класс | Файл(ы) данных | `getAdditionalSystemMessages` |
|-----------|-------|----------------|-------------------------------|
| Sliding Window | `SlidingWindowStrategy` | — | `emptyList()` |
| Sticky Facts | `StickyFactsStrategy` | `facts.json` | факты как `[system]` |
| Branching | `BranchingStrategy` | `branches.json` | `emptyList()` |
| Summary | `SummaryTruncationStrategy` | `summaries.json` | summary как `[system]` |
| Layered Memory | `LayeredMemoryStrategy` | `memory_*.json` × 3 | working + long-term как `[system]` |
| **Planning mode** | `TaskStateMachineAgent` | `task_state.json` | встраивает task-блок в system-промпт напрямую |

> `TaskStateMachineAgent` **не является** `ContextTruncationStrategy`. Это отдельный
> агент-обёртка над `ConfigurableAgent`. Переключение — через `SettingsData.isPlanningMode`,
> а не через `ContextStrategyType`.

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
3. Добавить вариант в `ContextStrategyType` (`ui/state/ChatUiState.kt`)
4. Добавить создание в `AppModule.buildStrategy()`
5. Добавить `displayName()` в `Dialog.kt`
6. Добавить capability accessor в `AgentChatViewModel` при необходимости

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
| **Промпт** | LLM получает существующие записи «для справки» и инструкцию возвращать **только новые факты** с ключами, которых ещё нет. |
| **Код** | `MemoryStorage.appendLongTerm()` фильтрует по `existingKeys` — даже если LLM вернул существующий ключ, он игнорируется. **Существующий ключ всегда побеждает.** |

### Capability pattern в ViewModel

```kotlin
private val layeredMemoryStrategy: LayeredMemoryStrategy?
    get() = agent.truncationStrategy as? LayeredMemoryStrategy
```

### ClearSession: LONG_TERM намеренно сохраняется

```kotlin
// agent.clearHistory() → strategy.clear() → memoryStorage.clearSession():
//   WORKING    → очищен
//   compressed → очищен
//   LONG_TERM  → НЕ тронут  ← persist между сессиями
```

---

## Planning mode — Task State Machine 🤖

### Ключевое отличие от стратегий 1–5

`TaskStateMachineAgent` — **не** `ContextTruncationStrategy`.
Это самостоятельный агент-обёртка, включаемый через Switch в диалоге настроек
(`SettingsData.isPlanningMode`).

```
ContextStrategyType (enum):          isPlanningMode (Boolean в SettingsData):
  SLIDING_WINDOW  ─┐                   false  → activeAgent = agent (+ любая стратегия)
  STICKY_FACTS    ─┤ независимы от     true   → activeAgent = taskStateMachineAgent
  BRANCHING       ─┤ Planning mode               (innerAgent: SimpleLLMAgent + Summary)
  SUMMARY         ─┤
  LAYERED_MEMORY  ─┘
```

Пользователь включает Planning mode через Switch «Planning mode 🤖» в диалоге настроек —
**не** через список стратегий.

### Концепция

`TaskStateMachineAgent` делегирует все операции `Agent` внутреннему
`innerAgent` (использует `SummaryTruncationStrategy` по умолчанию) через `by innerAgent`,
переопределяя только `send()` / `chatStream()` для встраивания логики автомата.

### Фазы автомата

```
PLANNING → EXECUTION → VALIDATION → DONE → (новая задача: PLANNING)
```

| Фаза | Что происходит |
|------|----------------|
| `PLANNING` | Постановка цели, декомпозиция задачи |
| `EXECUTION` | Выполнение шагов — итеративно |
| `VALIDATION` | Проверка результата на соответствие цели и инвариантам |
| `DONE` | Задача завершена, архивируется, можно начать новую |

### Поток данных

```
Пользовательский запрос
        ↓
buildSystemPrompt(taskState)   ← task-блок инжектируется в system-промпт
        ↓
innerAgent.chatStream()        ← основной LLM-вызов (с историей и summary)
        ↓
parseSignal(response)          ← ищем [PHASE_COMPLETE] / [PHASE: X] / [STEP: X] / [EXPECTED: X]
        ↓
validateWithLLM(response, invariants)  ← отдельный LLM-вызов (если есть инварианты)
    ↓ VALID                          ↓ INVALID (до maxRetries)
emit stream                   retry с violations в system-промпте
applySignalAndTransition()
taskStateStorage.save()
```

### Сигналы LLM → код

| Тег | Действие |
|-----|----------|
| `[PHASE_COMPLETE]` | Переход к следующей фазе по порядку |
| `[PHASE: execution]` | Предложить переход к конкретной фазе |
| `[STEP: описание]` | Обновить `currentStep` без смены фазы |
| `[EXPECTED: действие]` | Обновить `expectedAction` без смены фазы |
| _(ничего)_ | Состояние не меняется |

### Что уходит в LLM-запрос

```
[system]
  <existingSystemPrompt>         ← от innerAgent (profile + defaultSystemPrompt)

  ## Task State
  Phase: EXECUTION
  Current step: Написать функцию парсинга
  Expected action: Предоставить код функции
  Invariants for this phase:
  - Код должен быть на Kotlin

  ## Instructions
  At the end of your response, if the current step is complete, add: [PHASE_COMPLETE]
  ...

[history: last N messages]     ← из innerAgent (summary стратегия)
```

### Ручной переход (`advancePhase`)

```
Кнопка «→» в тулбаре (только в Planning mode)
    ↓
checkPhaseReadiness(state, history)  ← LLM-вызов: «готова ли фаза к переходу?»
    ↓ VALID                         ↓ INVALID
transition(nextPhase)         NotReady(reasons) → показываем диалог пользователю
taskStateStorage.save()
```

### Сигнатура класса

```kotlin
class TaskStateMachineAgent(
    private val innerAgent: ConfigurableAgent,
    private val api: StatsLLMApi,
    val taskStateStorage: TaskStateStorage,
    private val taskModel: String,
    val maxRetries: Int = DEFAULT_MAX_RETRIES
) : ConfigurableAgent by innerAgent {

    override suspend fun send(message: String): Flow<AgentStreamEvent>
    override suspend fun chatStream(request: AgentRequest): Flow<AgentStreamEvent>

    suspend fun startTask(phaseInvariants: List<PhaseInvariants>): TaskState
    suspend fun advancePhase(): AdvancePhaseResult
    suspend fun resetTask(): TaskState
    suspend fun getTaskState(): TaskState

    internal fun parseSignal(response: String): TaskSignal
}
```

### Persistence

| Файл | Содержимое | Очищается при `clearSession`? |
|------|-----------|-------------------------------|
| `task_state.json` | Полный `TaskState` (фаза, шаг, инварианты, архив) | ❌ нет — задача переживает паузы |

> `ClearSession` сбрасывает историю чата `innerAgent`, но **не трогает** `task_state.json`.
> Сбросить задачу — только через `ResetTask` (overflow-меню «Reset task»).

### Capability pattern в ViewModel

```kotlin
// Включение: SaveSettings(settingsData.copy(isPlanningMode = true))
// → handleSettingsUpdate() читает settingsData.isPlanningMode
// → _internalState.update { isPlanningMode = true }
// → taskStateMachineAgent.getTaskState()  ← загружаем сохранённое состояние

// Активный агент:
private val activeAgent: ConfigurableAgent
    get() = if (isPlanningMode && taskStateMachineAgent != null)
        taskStateMachineAgent else agent

// StartTask (кнопка ▶):
taskStateMachineAgent?.startTask(phaseInvariants)

// AdvancePhase (кнопка →):
when (val result = taskStateMachineAgent?.advancePhase()) {
    is AdvancePhaseResult.Advanced  → обновляем UI
    is AdvancePhaseResult.NotReady  → показываем причины в диалоге
    ...
}

// ResetTask (overflow):
taskStateMachineAgent?.resetTask()
```

### Тулбар в Planning mode

| Состояние | Кнопки тулбара |
|-----------|---------------|
| Planning mode выкл. | — (включается в Settings) |
| Planning mode вкл., нет задачи | ▶ `PlayArrow` — Start Task |
| Planning mode вкл., задача DONE | ▶ `PlayArrow` — Start new Task |
| Planning mode вкл., задача активна | `Refresh` — Advance Phase |
| Overflow (Planning mode вкл., задача активна) | «Reset task» |

---

## Сравнение всех стратегий

| | Sliding Window | Sticky Facts | Summary | Branching | Layered Memory | **Planning mode** |
|---|---|---|---|---|---|---|
| Что в LLM вместо старых сообщений | ничего | key-value факты | текст summary | ничего | working + long-term | task-блок в system |
| Автообновление | — | ✅ facts | ✅ summary | — | ✅ WORKING | ✅ после каждого ответа |
| Ручное обновление | — | ✅ ✨ | — | — | ✅ 💼🧠 | ✅ → фаза |
| Валидация ответа | — | — | — | — | — | ✅ LLM-вызов |
| Retry при нарушениях | — | — | — | — | — | ✅ до maxRetries |
| Persistence | — | `facts.json` | `summaries.json` | `branches.json` | 3 файла | `task_state.json` |
| Persist задачи между сессиями | — | — | — | — | — | ✅ всегда |
| Способ включения | Settings (список) | Settings (список) | Settings (список) | Settings (список) | Settings (список) | Settings (Switch) |
| Является `ContextTruncationStrategy` | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ |

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

1. Пользователь открывает настройки (кнопка ⚙️) → выбирает стратегию из списка
2. `SaveSettings` → `ViewModel.handleSettingsUpdate()` → `applyStrategyChange()`
3. `agent.updateTruncationStrategy(factory(newStrategyType))`
4. Кнопки тулбара (по стратегии):
   - `STICKY_FACTS`    → ✨ Refresh Facts
   - `BRANCHING`       → 🔖 Checkpoint, 🌿 Switch Branch
   - `LAYERED_MEMORY`  → 💼 Working Memory, 🧠 Long-Term Memory
5. Planning mode (Switch в том же диалоге) — **независим** от смены стратегии
