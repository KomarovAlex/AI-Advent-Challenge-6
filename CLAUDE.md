# CLAUDE.md

> Инструкции для Claude при работе с проектом aiChallenge

## 📖 Документация

| Нужно узнать | Читать |
|---|---|
| Структуру файлов проекта | [PROJECT_STRUCTURE.md](./PROJECT_STRUCTURE.md) |
| Архитектуру и поток данных | [docs/ARCHITECTURE.md](./docs/ARCHITECTURE.md) |
| Agent, AgentRequest, buildMessageList | [docs/AGENT.md](./docs/AGENT.md) |
| Компрессию истории (summary) | [docs/COMPRESSION.md](./docs/COMPRESSION.md) |
| API, persistence, модели | [docs/DATA_LAYER.md](./docs/DATA_LAYER.md) |
| ViewModel, MVI, UI | [docs/UI_LAYER.md](./docs/UI_LAYER.md) |
| Быстрый старт, типичные задачи | [docs/RECIPES.md](./docs/RECIPES.md) |

---

## 🎯 О проекте

**aiChallenge** — Android-приложение для чата с LLM с поддержкой:
- Стриминга ответов в реальном времени (OkHttp SSE)
- Компрессии истории через summary
- Сохранения истории между запусками

**Стек:** Kotlin, Jetpack Compose, Coroutines/Flow, OkHttp, Gson

---

## 🔧 Соглашения

### Kotlin
- `data class` для моделей, `sealed class` для состояний и событий
- Coroutines + Flow для асинхронности
- `Mutex` вместо `synchronized` в suspend-функциях
- Нет `runBlocking` в suspend-функциях

### Потокобезопасность в агенте
- `@Volatile` на полях, которые читаются в suspend и пишутся редко (`_config`, `_truncationStrategy`)
- `synchronized` — только в **не-suspend** методах (`updateConfig`, `updateTruncationStrategy`)
- `synchronized` в suspend — **запрещено**: блокирует поток при приостановке корутины

```kotlin
// ✅ Правильно
@Volatile private var _config: AgentConfig = initialConfig
override fun updateConfig(newConfig: AgentConfig) {          // не suspend
    synchronized(this) { _config = newConfig }
}

// ❌ Неправильно — suspend + synchronized
override suspend fun bad() { synchronized(this) { withContext(IO) { ... } } }
```

### Стратегии обрезки контекста
- Все стратегии реализуют `ContextTruncationStrategy`
- `getAdditionalSystemMessages()` — переопределить, если стратегия добавляет системные
  сообщения в LLM-запрос (например, summary). По умолчанию `emptyList()`
- Общая логика обрезки по токенам — `TruncationUtils.truncateByTokens()`, не дублировать
- Общий estimator — `TokenEstimators.default`, передавать через `TokenEstimator`

### Именование
- `Repository` — репозитории
- `ViewModel` — вьюмодели
- `State` — UI состояния
- `Agent` — классы агента

### Архитектура
- **MVI** в UI (Intent → ViewModel → State)
- **Strategy** для обрезки контекста
- **Decorator** для статистики API (`StatsTrackingLLMApi`)
- **Инверсия зависимостей**: `StatsLLMApi` определён в `agent/`, реализован в `data/`

---

## 🏛️ Граф зависимостей

```
ui/ → agent/ → domain/
ui/ → data/persistence/
data/ → agent/ (реализует StatsLLMApi)
data/ → domain/
di/  → все
```

`agent/` не зависит от Android — тестируется без эмулятора.

---

## 🚫 Чего не делать

- Не добавлять Android-зависимости в `agent/`
- Не обращаться к `AgentContext` снаружи агента — только через методы `Agent`
- Не передавать историю в `AgentRequest` — агент управляет ею сам через внутренний контекст
- Не обращаться к `SummaryStorage` снаружи агента — только через `agent.getSummaries()` / `agent.loadSummaries()`
- Не использовать `GlobalScope` — только `viewModelScope`
- Не блокировать main thread — IO на `Dispatchers.IO`
- Не использовать `runBlocking` в suspend-функциях
- Не включать `originalMessages` в LLM-запрос — только `content` из summary
- Не использовать `synchronized` в suspend-функциях — только `@Volatile` для чтения
- Не дублировать `userMessage` в `buildMessageList` — он уже в `_context` при `keepConversationHistory=true`
- Не дублировать `truncateByTokens` в стратегиях — использовать `TruncationUtils`

---

## ✅ Перед отправкой изменений

1. `./gradlew :app:compileDebugKotlin` — компиляция без ошибок
2. Обновить соответствующий файл в `docs/` если менялись интерфейсы или архитектура
3. Обновить `PROJECT_STRUCTURE.md` если добавлены/удалены файлы
