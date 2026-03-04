# 🗜️ Стратегии управления контекстом

## Обзор

Все стратегии 1–5 реализуют `ContextTruncationStrategy` и подключаются к `SimpleLLMAgent`.
Стратегия 6 (`TaskStateMachineAgent`) — самостоятельная сущность-обёртка, не реализует
`ContextTruncationStrategy` напрямую.
Переключение — через `ContextStrategyType` в настройках UI.

| Стратегия | Класс | Файл(ы) данных | `getAdditionalSystemMessages` |
|-----------|-------|----------------|-------------------------------|
| Sliding Window | `SlidingWindowStrategy` | — | `emptyList()` |
| Sticky Facts | `StickyFactsStrategy` | `facts.json` | факты как `[system]` |
| Branching | `BranchingStrategy` | `branches.json` | `emptyList()` |
| Summary | `SummaryTruncationStrategy` | `summaries.json` | summary как `[system]` |
| Layered Memory | `LayeredMemoryStrategy` | `memory_*.json` × 3 | working + long-term как `[system]` |
| **Task State Machine** | `TaskStateMachineAgent` | `task_state.json` | встраивает task-блок в system-промпт напрямую |

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

## Стратегия 6 — Task State Machine 🤖

### Концепция

`TaskStateMachineAgent` — **самостоятельная сущность-обёртка** над `ConfigurableAgent`.
Не является `ContextTruncationStrategy`. Делегирует все операции `Agent` внутреннему
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

LLM добавляет теги **в конце ответа**:

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
  - Не использовать глобальные переменные

  ## Instructions
  At the end of your response, if the current step is complete, add: [PHASE_COMPLETE]
  To suggest a specific next phase: [PHASE: planning|execution|validation|done]
  To update the current step description: [STEP: description]
  To update the expected action: [EXPECTED: description]

[history: last N messages]     ← из innerAgent (summary стратегия)
```

### Валидация инвариантов

Отдельный LLM-вызов после каждого ответа (только если `currentInvariants` не пустые):

```
User → LLM: "Check if response complies with invariants:
  - Код должен быть на Kotlin
  Response: <полный ответ>"

LLM → "VALID"  или  "INVALID\n- нарушение 1\n- нарушение 2"
```

- `VALID` → отдаём ответ пользователю + обновляем состояние
- `INVALID` → повторный запрос с нарушениями в промпте (до `maxRetries` раз)
- После исчерпания `maxRetries` → отдаём ответ с предупреждением `⚠️`

### Ручной переход (`advancePhase`)

```
Кнопка «→» в тулбаре
    ↓
checkPhaseReadiness(state, history)  ← LLM-вызов: «готова ли фаза к переходу?»
    ↓ VALID                         ↓ INVALID
transition(nextPhase)         NotReady(reasons) → показываем диалог пользователю
taskStateStorage.save()
```

### Архивирование задачи

При переходе в `DONE` — LLM-вызов для создания краткого summary завершённой задачи.
Summary сохраняется в `ArchivedTask` внутри `TaskState.archivedTasks`.

### Пауза и продолжение

```
Пользователь закрыл приложение на фазе EXECUTION
    ↓
task_state.json сохранён: { phase: EXECUTION, currentStep: "...", invariants: [...] }

Пользователь открыл приложение
    ↓
ViewModel.loadSavedHistory() → taskStateMachineAgent.getTaskState()
    ↓
_internalState.update { it.copy(taskState = savedTaskState) }
    ↓
Следующий запрос: system-промпт содержит актуальный ## Task State блок
    ↓
Агент продолжает с того же места без повторных объяснений ✅
```

### Сигнатура класса

```kotlin
class TaskStateMachineAgent(
    private val innerAgent: ConfigurableAgent,    // SummaryTruncationStrategy внутри
    private val api: StatsLLMApi,
    val taskStateStorage: TaskStateStorage,        // persist → task_state.json
    private val taskModel: String,
    val maxRetries: Int = DEFAULT_MAX_RETRIES      // 3 по умолчанию
) : ConfigurableAgent by innerAgent {

    // Переопределяет send() и chatStream() — добавляет task-блок и цикл валидации
    override suspend fun send(message: String): Flow<AgentStreamEvent>
    override suspend fun chatStream(request: AgentRequest): Flow<AgentStreamEvent>

    // Task lifecycle:
    suspend fun startTask(phaseInvariants: List<PhaseInvariants>): TaskState
    suspend fun advancePhase(): AdvancePhaseResult   // LLM-валидация готовности
    suspend fun resetTask(): TaskState               // архивирует + сбрасывает
    suspend fun getTaskState(): TaskState

    // Internal: parseSignal() помечен internal для тестируемости
    internal fun parseSignal(response: String): TaskSignal
}
```

### TaskState — модель персистентности

```kotlin
data class TaskState(
    val taskId: String = "",
    val phase: TaskPhase = TaskPhase.PLANNING,
    val currentStep: String = "",
    val expectedAction: String = "",
    val phaseInvariants: List<PhaseInvariants> = emptyList(),  // инварианты по фазам
    val isActive: Boolean = false,
    val retryCount: Int = 0,
    val createdAt: Long = ...,
    val updatedAt: Long = ...,
    val archivedTasks: List<ArchivedTask> = emptyList()        // история завершённых
) {
    val currentInvariants: List<String>     // инварианты текущей фазы
        get() = phaseInvariants.firstOrNull { it.phase == phase }?.rules ?: emptyList()
}
```

### TaskStateStorage

```kotlin
interface TaskStateStorage {
    suspend fun getState(): TaskState
    suspend fun saveState(state: TaskState)
    suspend fun reset()                    // сбрасывает до isActive=false, архив сохраняет
}
// InMemoryTaskStateStorage   — для тестов
// JsonTaskStateStorage       — task_state.json (data/persistence/)
```

### AdvancePhaseResult — результат ручного перехода

```kotlin
sealed class AdvancePhaseResult {
    data class Advanced(val newState: TaskState) : AdvancePhaseResult()
    data class NotReady(val reasons: List<String>) : AdvancePhaseResult()
    data object NoActiveTask : AdvancePhaseResult()
    data object AlreadyDone  : AdvancePhaseResult()
}
```

### Persistence

| Файл | Содержимое | Очищается при `clearSession`? |
|------|-----------|-------------------------------|
| `task_state.json` | Полный `TaskState` (фаза, шаг, инварианты, архив) | ❌ нет — задача переживает паузы |

> `ClearSession` (кнопка 🧹) сбрасывает историю чата `innerAgent`,
> но **не трогает** `task_state.json` — пользователь продолжает задачу после паузы.
> Сбросить задачу можно только через `ResetTask` (overflow-меню «Reset task»).

### Capability pattern в ViewModel

```kotlin
// ViewModel получает taskStateMachineAgent из AppModule
class AgentChatViewModel(
    private val agent: ConfigurableAgent,
    ...
    private val taskStateMachineAgent: TaskStateMachineAgent? = null
)

// Активный агент — зависит от выбранной стратегии
private val activeAgent: ConfigurableAgent
    get() = if (activeStrategy == TASK_STATE_MACHINE && taskStateMachineAgent != null)
        taskStateMachineAgent else agent

// Загрузка при старте:
val savedTaskState = taskStateMachineAgent?.getTaskState()

// StartTask (кнопка ▶ в тулбаре):
taskStateMachineAgent?.startTask(phaseInvariants)

// AdvancePhase (кнопка → в тулбаре):
when (val result = taskStateMachineAgent?.advancePhase()) {
    is AdvancePhaseResult.Advanced  → обновляем UI
    is AdvancePhaseResult.NotReady  → показываем причины в диалоге
    ...
}

// ResetTask (overflow-меню):
taskStateMachineAgent?.resetTask()
```

### Тулбар TASK_STATE_MACHINE

| Состояние | Кнопки |
|-----------|--------|
| Нет активной задачи | ▶ `PlayArrow` — Start Task |
| Задача DONE | ▶ `PlayArrow` — Start new Task |
| Задача активна | `Refresh` — Advance Phase (с LLM-валидацией готовности) |
| Overflow | «Reset task» — сбросить/архивировать, «Settings» — maxRetries |

---

## Сравнение всех стратегий

| | Sliding Window | Sticky Facts | Summary | Branching | Layered Memory | **Task State Machine** |
|---|---|---|---|---|---|---|
| Что в LLM вместо старых сообщений | ничего | key-value факты | текст summary | ничего | working + long-term | task-блок в system |
| Автообновление | — | ✅ facts | ✅ summary | — | ✅ WORKING | ✅ после каждого ответа |
| Ручное обновление | — | ✅ ✨ | — | — | ✅ 💼🧠 | ✅ → фаза |
| Валидация ответа | — | — | — | — | — | ✅ LLM-вызов |
| Retry при нарушениях | — | — | — | — | — | ✅ до maxRetries |
| Persistence | — | `facts.json` | `summaries.json` | `branches.json` | 3 файла | `task_state.json` |
| Persist задачи между сессиями | — | — | — | — | — | ✅ всегда |
| LLM-вызовы помимо основного | — | 1 | 1 | — | до 2 | до 2 (валидация + archiving) |
| Архив завершённых | — | — | — | — | — | ✅ ArchivedTask |

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
3. Для стратегий 1–5: `agent.updateTruncationStrategy(factory(newStrategyType))`
4. Для `TASK_STATE_MACHINE`: стратегия `innerAgent` не меняется — `taskStateMachineAgent` уже готов
5. Кнопки тулбара:
   - `STICKY_FACTS`        → ✨ Refresh Facts
   - `BRANCHING`           → 🔖 Checkpoint, 🌿 Switch Branch
   - `LAYERED_MEMORY`      → 💼 Working Memory, 🧠 Long-Term Memory
   - `TASK_STATE_MACHINE`  → ▶ Start Task / → Advance Phase
