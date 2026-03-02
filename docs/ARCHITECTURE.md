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

---

## Осознанный компромисс (ChatHistoryRepository)

`ChatHistoryRepository` и `ChatSession` живут в `data/persistence/` — оба оперируют
persistence-моделями (`Persisted*`). Перенос интерфейса в `domain/` потянул бы за собой
весь `ChatSession` — это хуже. `ui/ → data/persistence/` — допустимо для проекта
без отдельного use case слоя.

---

## Поток данных

```
ChatIntent
    → ViewModel.handleIntent()
    → agent.send(message)
    → SimpleLLMAgent.chatStream()
        → buildMessageList()          [system] + [memory layers] + [history]
        → api.sendMessageStream()     OkHttp SSE
        → Flow<StatsStreamResult>
    → Flow<AgentStreamEvent>
    → ViewModel._internalState
    → ChatUiState
    → ChatScreen
```

---

## Разделение ответственности

| Компонент | Ответственность |
|-----------|-----------------|
| `AgentContext` | Приватное хранилище сообщений (`synchronized`) |
| `SummaryStorage` | Хранилище summaries (`Mutex` + IO) — деталь стратегии |
| `FactsStorage` | Хранилище фактов — деталь стратегии |
| `BranchStorage` | Хранилище веток — деталь стратегии |
| `MemoryStorage` | Хранилище трёх слоёв памяти — деталь LayeredMemoryStrategy |
| `Agent` | Инкапсуляция истории, отправка запросов, делегирование стратегии |
| `ContextTruncationStrategy` | Логика обрезки, компрессии, очистки своего состояния |
| `ViewModel` | MVI: Intent → State, capability accessors для стратегий |
| `ChatHistoryRepository` | Persistence сессий (в `data/persistence/`) |

---

## Инкапсуляция: что видно снаружи агента

```
Снаружи агента:                         Внутри SimpleLLMAgent:
  agent.conversationHistory        ←──  _context.getHistory()          (read-only)
  agent.truncationStrategy         ←──  _truncationStrategy             (read-only)
  agent.send() / chatStream()      ──►  buildMessageList() + API call
  agent.addToHistory()             ──►  _context.addMessage()
  agent.clearHistory()             ──►  _context.clear()
                                        + _truncationStrategy?.clear()  (делегирование)
  agent.initBranches()             ──►  (strategy as BranchingStrategy).ensureInitialized()
                                        + _context.replaceHistory()
  agent.createCheckpoint()         ──►  (strategy as BranchingStrategy).createCheckpoint()
  agent.switchToBranch()           ──►  (strategy as BranchingStrategy).switchToBranch()
                                        + _context.replaceHistory()
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
| `getSummaries` | ❌ нет — чистый I/O со storage | `truncationStrategy as? Summary...` |
| `getFacts` | ❌ нет — чистый I/O со storage | `truncationStrategy as? StickyFacts...` |
| `getWorkingMemory` | ❌ нет — чистый I/O со storage | `truncationStrategy as? LayeredMemory...` |
| `getLongTermMemory` | ❌ нет — чистый I/O со storage | `truncationStrategy as? LayeredMemory...` |
| `refreshWorkingMemory` | ❌ нет — LLM-вызов + I/O | `truncationStrategy as? LayeredMemory...` |
| `refreshLongTermMemory` | ❌ нет — LLM-вызов + I/O | `truncationStrategy as? LayeredMemory...` |

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

### Особый случай: LayeredMemoryStrategy.clear()

`LayeredMemoryStrategy.clear()` вызывает `memoryStorage.clearSession()` — это очищает
только WORKING и compressed. **LONG_TERM намеренно не очищается** при сбросе сессии:

```kotlin
// MemoryStorage.clearSession():
_working    = emptyList()   // ← очищается
_compressed = emptyList()   // ← очищается
// _longTerm               // ← намеренно не трогаем
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
| `SimpleLLMAgent._config` | `@Volatile` + `synchronized` в setter | Читается в suspend без блокировки |
| `SimpleLLMAgent._truncationStrategy` | `@Volatile` + `synchronized` в setter | То же |

### JsonMemoryStorage — почему три Mutex

```kotlin
// Три независимых слоя — нет смысла блокировать весь storage при записи в один слой
private val workingMutex    = Mutex()
private val longTermMutex   = Mutex()
private val compressedMutex = Mutex()
```

Это позволяет одновременно читать LONG_TERM и записывать WORKING — без лишних блокировок.

---

## Что уходит в LLM

```
✅ system prompt
✅ getAdditionalSystemMessages() от стратегии:
    Summary:        [system: summary text]
    StickyFacts:    [system: "Key facts: ..."]
    LayeredMemory:  [system: "Long-term memory: ..."] + [system: "Working memory: ..."]
✅ _context.getHistory()                        (активные сообщения / SHORT_TERM)

❌ ConversationSummary.originalMessages         (только UI)
❌ compressedMessages из Facts/Memory           (только UI)
❌ вся история при keepConversationHistory=false (только текущий userMessage)
```
