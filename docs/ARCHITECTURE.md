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
        → buildMessageList()          [system] + [summaries/facts] + [history]
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
  summaryStrategy?.getSummaries()  ←──  summaryStorage.getSummaries()
  summaryStrategy?.loadSummaries() ──►  summaryStorage.loadSummaries()
  factsStrategy?.getFacts()        ←──  factsStorage.getFacts()
  factsStrategy?.refreshFacts()    ──►  LLM call + factsStorage.replaceFacts()
  factsStrategy?.loadFacts()       ──►  factsStorage.replaceFacts()
```

---

## ISP: почему branches в Agent, а summaries/facts — нет

| Операция | Требует синхронизации `_context`? | Где |
|----------|-----------------------------------|-----|
| `switchToBranch` | ✅ да — `_context.replaceHistory(branch.messages)` | `Agent` |
| `initBranches` | ✅ да — `_context.replaceHistory(activeBranch.messages)` | `Agent` |
| `createCheckpoint` | ✅ да — читает `_context.getHistory()` | `Agent` |
| `getSummaries` | ❌ нет — чистый I/O со storage | `truncationStrategy as? Summary...` |
| `loadSummaries` | ❌ нет — чистый I/O со storage | `truncationStrategy as? Summary...` |
| `getFacts` | ❌ нет — чистый I/O со storage | `truncationStrategy as? StickyFacts...` |
| `refreshFacts` | ❌ нет — LLM-вызов + I/O | `truncationStrategy as? StickyFacts...` |

---

## OCP: добавление новой стратегии

До рефакторинга — `clearHistory()` требовал правки при каждой новой стратегии:

```kotlin
// ❌ Нарушение OCP — when по типам
override suspend fun clearHistory() {
    _context.clear()
    when (strategy) {
        is SummaryTruncationStrategy -> strategy.clearSummaries()
        is StickyFactsStrategy       -> strategy.clearFacts()
        is BranchingStrategy         -> strategy.clearBranches()
        else -> Unit
    }
}
```

После рефакторинга — `clear()` входит в контракт стратегии:

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
| `SimpleLLMAgent._config` | `@Volatile` + `synchronized` в setter | Читается в suspend без блокировки |
| `SimpleLLMAgent._truncationStrategy` | `@Volatile` + `synchronized` в setter | То же |

```kotlin
// ✅ @Volatile — безопасное чтение в suspend без блокировки потока
@Volatile private var _config: AgentConfig = initialConfig

// ✅ synchronized только в не-suspend методах
override fun updateConfig(newConfig: AgentConfig) {
    synchronized(this) { _config = newConfig }
}

// ✅ Snapshot — единое согласованное чтение _config
override suspend fun send(message: String): Flow<AgentStreamEvent> {
    val config = _config   // одно volatile-чтение
    val request = AgentRequest(model = config.defaultModel, temperature = config.defaultTemperature, ...)
    return chatStream(request)
}

// ❌ synchronized в suspend блокирует поток при приостановке корутины
suspend fun bad() { synchronized(lock) { withContext(IO) { } } }

// ✅ Mutex приостанавливает корутину, поток свободен
suspend fun good() { mutex.withLock { withContext(IO) { } } }
```

---

## Что уходит в LLM

```
✅ system prompt
✅ getAdditionalSystemMessages() от стратегии  (summary / facts)
✅ _context.getHistory()                        (активные сообщения)

❌ ConversationSummary.originalMessages         (только UI)
❌ вся история при keepConversationHistory=false (только текущий userMessage)
```
