# 🏛️ Архитектура

## Слои

```
UI (Compose) → ViewModel (MVI) → Agent → domain
                    ↓                ↑
              Persistence (JSON)   data  (реализует agent.StatsLLMApi)
```

| Слой | Пакет | Зависит от |
|------|-------|------------|
| UI | `ui/` | Agent, Domain, data/persistence |
| Agent | `agent/` | Domain |
| Data | `data/` | Agent (реализует StatsLLMApi), Domain |
| Domain | `domain/` | — |
| DI | `di/` | все |

> `agent/` не зависит от Android и от `data/` — тестируется без эмулятора.

---

## Инверсия зависимости (StatsLLMApi)

```
agent/LLMApi.kt              interface StatsLLMApi        ← определён здесь
data/StatsTrackingLLMApi.kt  class StatsTrackingLLMApi    ← реализован здесь
di/AppModule.kt              val statsLLMApi: StatsLLMApi = StatsTrackingLLMApi(...)
```

Зависимость направлена внутрь: `data` → `agent` → `domain`. ✅

Тот же принцип применён для профиля:

```
agent/profile/ProfileSystemPromptProvider.kt        interface ProfileSystemPromptProvider   ← здесь
agent/profile/ActiveProfileSystemPromptProvider.kt  class ActiveProfileSystemPromptProvider ← здесь
di/AppModule.kt  profilePromptProvider = ActiveProfileSystemPromptProvider { profileStorage... }
```

`agent/` видит только интерфейс — зависимостей от `JsonProfileStorage` и Android нет. ✅

---

## ISP: разделение Agent и ConfigurableAgent

```
agent/Agent.kt              interface Agent               ← read-only контракт
agent/ConfigurableAgent.kt  interface ConfigurableAgent   ← мутирующее расширение
agent/SimpleLLMAgent.kt     class SimpleLLMAgent          ← единственная реализация
```

`AgentChatViewModel` зависит от `ConfigurableAgent` — явно документирует право на мутацию.
Тесты и headless-потребители зависят от `Agent` — не получают ненужных методов.
`AgentFactory` и `buildAgent` DSL возвращают `ConfigurableAgent` — нет unsafe-cast. ✅

---

## Осознанный компромисс (ChatHistoryRepository)

`ChatHistoryRepository` и `ChatSession` живут в `data/persistence/` — оба оперируют
persistence-моделями (`Persisted*`). Перенос интерфейса в `domain/` потянул бы за собой
весь `ChatSession` — это хуже. `ui/ → data/persistence/` — допустимо для проекта
без отдельного use case слоя.

---

## Поток данных — обычный агент (стратегии 1–5)

```
ChatIntent
    → ViewModel.handleIntent()
    → agent.send(message)
    → SimpleLLMAgent.chatStream()
        → buildMessageList()          [profile block] + [system] + [strategy system] + [history]
        → api.sendMessageStream()     OkHttp SSE
        → Flow<StatsStreamResult>
    → Flow<AgentStreamEvent>
    → ViewModel._internalState
    → ChatUiState
    → ChatScreen
```

## Поток данных — Planning mode (Task State Machine)

```
Пользователь открывает настройки → включает Switch «Planning mode 🤖» → нажимает Save
    → ChatIntent.SaveSettings(settingsData.copy(isPlanningMode = true))
    → ViewModel.handleSettingsUpdate()
    → isPlanningMode = true           ← activeAgent = taskStateMachineAgent
    → taskStateMachineAgent.getTaskState()  ← загружаем сохранённое состояние

ChatIntent.SendMessage
    → ViewModel.handleIntent()
    → activeAgent.send(message)       ← activeAgent = taskStateMachineAgent
    → TaskStateMachineAgent.send()
        → buildSystemPrompt(taskState)  ← инжектирует ## Task State блок
        → innerAgent.chatStream()       ← SimpleLLMAgent + SummaryStrategy
            → api.sendMessageStream()
        ↓ накапливает fullResponse
        → parseSignal(fullResponse)     ← [PHASE_COMPLETE] / [PHASE: X] / [STEP: X]
        → validateWithLLM()             ← отдельный LLM-вызов (если есть инварианты)
            ↓ VALID                          ↓ INVALID (retry до maxRetries)
        emit stream                   buildRetryRequest(violations)
        applySignalAndTransition()    повторный innerAgent.chatStream()
        taskStateStorage.save()
    → Flow<AgentStreamEvent>
    → ViewModel._internalState
        → taskState = taskStateMachineAgent.getTaskState()
    → ChatUiState (taskState, phase в заголовке тулбара)
    → ChatScreen
```

### Независимость Planning mode и стратегии контекста

Planning mode и `ContextStrategyType` — **две независимые оси**:

```
isPlanningMode=false, activeStrategy=SUMMARY        ← обычный чат с суммаризацией
isPlanningMode=true,  activeStrategy=SUMMARY        ← Planning mode (TSM использует свой innerAgent)
isPlanningMode=false, activeStrategy=LAYERED_MEMORY ← чат с трёхслойной памятью
isPlanningMode=true,  activeStrategy=LAYERED_MEMORY ← Planning mode поверх (независимо)
```

`TaskStateMachineAgent` использует собственный `innerAgent` со своей `SummaryStrategy` —
не конфликтует с основным `agent`.

### Пауза и продолжение без повторных объяснений

```
Сессия 1: пользователь работает на фазе EXECUTION (Planning mode включён)
    → каждый ответ: taskStateStorage.save() → task_state.json

Приложение закрыто

Сессия 2: пользователь открывает Settings → Planning mode уже включён (сохранено в SettingsData)
    → handleSettingsUpdate() → taskStateMachineAgent.getTaskState()
    → _internalState.taskState = { phase: EXECUTION, currentStep: "...", ... }
    → следующий запрос: buildSystemPrompt() содержит ## Task State блок
    → LLM продолжает задачу без повторного объяснения контекста ✅
```

---

## Разделение ответственности

| Компонент | Ответственность |
|-----------|-----------------|
| `Agent` | Read-only контракт: история, запросы, ветки |
| `ConfigurableAgent` | Мутирующее расширение `Agent`: смена конфига и стратегии в рантайме |
| `SimpleLLMAgent` | Реализация `ConfigurableAgent`: инкапсуляция истории, отправка запросов, делегирование стратегии |
| `TaskStateMachineAgent` | Обёртка над `ConfigurableAgent`: конечный автомат задачи, валидация инвариантов, архивирование |
| `AgentContext` | Приватное хранилище сообщений (`synchronized`) |
| `ProfileSystemPromptProvider` | Формирование блока профиля для system-промпта (динамически при каждом запросе) |
| `ContextTruncationStrategy` | Логика обрезки, компрессии, очистки своего состояния |
| `SummaryStorage` | Хранилище summaries (`Mutex` + IO) — деталь стратегии |
| `FactsStorage` | Хранилище фактов — деталь стратегии |
| `BranchStorage` | Хранилище веток — деталь стратегии |
| `MemoryStorage` | Хранилище трёх слоёв памяти — деталь LayeredMemoryStrategy |
| `TaskStateStorage` | Хранилище состояния задачи — деталь TaskStateMachineAgent |
| `ViewModel` | MVI: Intent → State, capability accessors для стратегий, обработка Planning mode через SaveSettings |
| `ChatHistoryRepository` | Persistence сессий (в `data/persistence/`) |

---

## Инкапсуляция: что видно снаружи агента

```
Снаружи (Agent / ConfigurableAgent):      Внутри SimpleLLMAgent:
  agent.conversationHistory          ←──  _context.getHistory()          (read-only)
  agent.truncationStrategy           ←──  _truncationStrategy             (read-only)
  agent.send() / chatStream()        ──►  buildMessageList() + API call
  agent.addToHistory()               ──►  _context.addMessage()
  agent.clearHistory()               ──►  _context.clear()
                                          + _truncationStrategy?.clear()  (делегирование)
  agent.initBranches()               ──►  (strategy as BranchingStrategy).ensureInitialized()
                                          + _context.replaceHistory()
  agent.createCheckpoint()           ──►  (strategy as BranchingStrategy).createCheckpoint()
  agent.switchToBranch()             ──►  (strategy as BranchingStrategy).switchToBranch()
                                          + _context.replaceHistory()

  // только через ConfigurableAgent:
  agent.updateConfig(newConfig)      ──►  synchronized { _config = newConfig }
  agent.updateTruncationStrategy(s)  ──►  synchronized { _truncationStrategy = s }
```

```
TaskStateMachineAgent — дополнительные методы (не в ConfigurableAgent):
  taskAgent.startTask(phaseInvariants)   ──►  TaskState (phase=PLANNING, isActive=true)
  taskAgent.advancePhase()               ──►  LLM-валидация → AdvancePhaseResult
  taskAgent.resetTask()                  ──►  архивировать + сбросить
  taskAgent.getTaskState()               ←──  taskStateStorage.getState()
```

```
Снаружи через Capability pattern (ViewModel):
  summaryStrategy?.getSummaries()       ←──  summaryStorage.getSummaries()
  summaryStrategy?.loadSummaries()      ──►  summaryStorage.loadSummaries()
  factsStrategy?.getFacts()             ←──  factsStorage.getFacts()
  factsStrategy?.refreshFacts()         ──►  LLM call + factsStorage.replaceFacts()
  layeredMemoryStrategy?.getWorkingMemory()   ←──  memoryStorage.getWorking()
  layeredMemoryStrategy?.getLongTermMemory()  ←──  memoryStorage.getLongTerm()
  layeredMemoryStrategy?.refreshWorkingMemory()  ──►  LLM call + memoryStorage.replaceWorking()
  layeredMemoryStrategy?.refreshLongTermMemory() ──►  LLM call + memoryStorage.replaceLongTerm()
```

---

## ISP: почему branches в Agent, а summaries/facts/memory — нет

| Операция | Требует синхронизации `_context`? | Где |
|----------|-----------------------------------|-----|
| `switchToBranch` | ✅ да — `_context.replaceHistory(branch.messages)` | `Agent` |
| `initBranches` | ✅ да — `_context.replaceHistory(activeBranch.messages)` | `Agent` |
| `createCheckpoint` | ✅ да — читает `_context.getHistory()` | `Agent` |
| `updateConfig` | ❌ нет — мутация конфига агента | `ConfigurableAgent` |
| `updateTruncationStrategy` | ❌ нет — замена стратегии | `ConfigurableAgent` |
| `getSummaries` | ❌ нет — чистый I/O со storage | `truncationStrategy as? Summary...` |
| `getFacts` | ❌ нет — чистый I/O со storage | `truncationStrategy as? StickyFacts...` |
| `getWorkingMemory` | ❌ нет — чистый I/O со storage | `truncationStrategy as? LayeredMemory...` |
| `getLongTermMemory` | ❌ нет — чистый I/O со storage | `truncationStrategy as? LayeredMemory...` |
| `refreshWorkingMemory` | ❌ нет — LLM-вызов + I/O | `truncationStrategy as? LayeredMemory...` |
| `refreshLongTermMemory` | ❌ нет — LLM-вызов + I/O | `truncationStrategy as? LayeredMemory...` |
| `startTask` | ❌ нет — только taskStateStorage | `taskStateMachineAgent` |
| `advancePhase` | ❌ нет — LLM-вызов + I/O | `taskStateMachineAgent` |

---

## OCP: добавление новой стратегии

`clearHistory()` делегирует в `clear()` стратегии — новая стратегия просто переопределяет его:

```kotlin
// ✅ OCP соблюдён — новая стратегия просто переопределяет clear()
override suspend fun clearHistory() {
    _context.clear()
    _truncationStrategy?.clear()
}
```

---

## Потокобезопасность

| Компонент | Механизм | Почему |
|-----------|----------|--------|
| `SimpleAgentContext` | `synchronized` | Методы синхронные, suspend-точек нет |
| `InMemorySummaryStorage` | `Mutex` | suspend + IO |
| `JsonSummaryStorage` | `Mutex` | suspend + IO |
| `JsonFactsStorage` | `Mutex` | suspend + IO |
| `JsonBranchStorage` | `Mutex` | suspend + IO |
| `JsonMemoryStorage` | три `Mutex` (по одному на слой) | suspend + IO, слои независимы |
| `InMemoryMemoryStorage` | `Mutex` | suspend + IO |
| `InMemoryTaskStateStorage` | `Mutex` | suspend + IO |
| `JsonTaskStateStorage` | `Mutex` | suspend + IO |
| `JsonProfileStorage` | `Mutex` | suspend + IO |
| `SimpleLLMAgent._config` | `@Volatile` + `synchronized` в setter | Читается в suspend без блокировки |
| `SimpleLLMAgent._truncationStrategy` | `@Volatile` + `synchronized` в setter | То же |

---

## Что уходит в LLM

### Стратегии 1–5 (через SimpleLLMAgent + DefaultPromptBuilder)

```
✅ [system] ## User Profile (от ProfileSystemPromptProvider, если facts не пусты)
✅ [system] defaultSystemPrompt / request.systemPrompt
✅ getAdditionalSystemMessages() от стратегии:
    Summary:        [system: summary text]
    StickyFacts:    [system: "Key facts: ..."]
    LayeredMemory:  [system: "Long-term memory: ..."] + [system: "Working memory: ..."]
✅ _context.getHistory()                        (активные сообщения / SHORT_TERM)

❌ ConversationSummary.originalMessages         (только UI)
❌ compressedMessages из Facts/Memory           (только UI)
❌ вся история при keepConversationHistory=false (только текущий userMessage)
❌ Profile.rawText                              (только источник для извлечения фактов)
```

### Planning mode (через TaskStateMachineAgent → innerAgent)

```
✅ [system] ## User Profile              (от profilePromptProvider innerAgent)
✅ [system] defaultSystemPrompt
✅ [system] ## Task State                ← инжектируется TaskStateMachineAgent.buildSystemPrompt()
            Phase: EXECUTION
            Current step: ...
            Invariants: ...
            ## Instructions: [PHASE_COMPLETE] / [PHASE: X] / [STEP: X] / [EXPECTED: X]
✅ summary от SummaryTruncationStrategy  (innerAgent)
✅ _context.getHistory()                 (последние N сообщений, innerAgent)

❌ task_state.json напрямую             (только source of truth для восстановления)
❌ ArchivedTask                         (только архив, не идёт в LLM)
```
