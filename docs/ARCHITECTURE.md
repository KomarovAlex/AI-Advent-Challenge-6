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

## Инверсия зависимости (StatsLLMApi)

```
agent/LLMApi.kt              interface StatsLLMApi        ← определён здесь
data/StatsTrackingLLMApi.kt  class StatsTrackingLLMApi    ← реализован здесь
di/AppModule.kt              val statsLLMApi: StatsLLMApi = StatsTrackingLLMApi(...)
```

Зависимость направлена внутрь: `data` → `agent` → `domain`. ✅

## Осознанный компромисс (ChatHistoryRepository)

`ChatHistoryRepository` и `ChatSession` живут в `data/persistence/` — оба оперируют
persistence-моделями (`Persisted*`). Перенос интерфейса в `domain/` потянул бы за собой
весь `ChatSession` — это хуже. `ui/ → data/persistence/` — допустимо для проекта
без отдельного use case слоя.

## Поток данных

```
ChatIntent
    → ViewModel.handleIntent()
    → agent.send(message)
    → SimpleLLMAgent.chatStream()
        → buildMessageList()          [system] + [summaries] + [history]
        → api.sendMessageStream()     OkHttp SSE
        → Flow<StatsStreamResult>
    → Flow<AgentStreamEvent>
    → ViewModel._internalState
    → ChatUiState
    → ChatScreen
```

## Разделение ответственности

| Компонент | Ответственность |
|-----------|-----------------|
| `AgentContext` | Приватное хранилище сообщений (`synchronized`) |
| `SummaryStorage` | Приватное хранилище summaries (`Mutex` + IO) — деталь агента |
| `Agent` | Инкапсуляция истории + summaries, отправка запросов, обрезка |
| `TruncationStrategy` | Логика обрезки / компрессии (`suspend`) |
| `ViewModel` | MVI: Intent → State, сборка `allMessages`, persistence |
| `ChatHistoryRepository` | Persistence сессий (в `data/persistence/`) |

## Инкапсуляция в агенте

```
Снаружи агента:                    Внутри SimpleLLMAgent:
  agent.conversationHistory   ←──  _context.getHistory()        (read-only)
  agent.getSummaries()        ←──  _truncationStrategy.getSummaries()
  agent.send()                ──►  addMessageWithTruncation()
  agent.addToHistory()        ──►  _context.addMessage()
  agent.loadSummaries()       ──►  _truncationStrategy.loadSummaries()
  agent.clearHistory()        ──►  _context.clear() + clearSummaries()
```

## Потокобезопасность

| Компонент | Механизм | Почему |
|-----------|----------|--------|
| `SimpleAgentContext` | `synchronized` | Синхронные методы |
| `SummaryStorage` | `Mutex` | suspend + IO |
| `SimpleLLMAgent._config` | `synchronized(this)` | Мутация из разных корутин |

```kotlin
// ❌ synchronized блокирует поток внутри suspend
suspend fun bad() { synchronized(lock) { withContext(IO) { } } }

// ✅ Mutex приостанавливает корутину, поток свободен
suspend fun good() { mutex.withLock { withContext(IO) { } } }
```

## Что уходит в LLM

```
✅ system prompt
✅ ConversationSummary.content      (из SummaryTruncationStrategy)
✅ _context.getHistory()            (активные сообщения)

❌ ConversationSummary.originalMessages  (только UI)
```
