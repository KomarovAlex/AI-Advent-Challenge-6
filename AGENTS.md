# AI Agent Instructions

> Инструкции для AI-ассистентов, работающих с этим проектом

## 📖 Документация

| Нужно узнать | Читать |
|---|---|
| Структуру файлов проекта | [PROJECT_STRUCTURE.md](./PROJECT_STRUCTURE.md) |
| Архитектуру и поток данных | [docs/ARCHITECTURE.md](./docs/ARCHITECTURE.md) |
| Agent, ConfigurableAgent, buildMessageList | [docs/AGENT.md](./docs/AGENT.md) |
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

### Именование
- `Repository` — репозитории
- `ViewModel` — вьюмодели
- `State` — UI состояния
- `Agent` — классы агента

### Архитектура
- **MVI** в UI (Intent → ViewModel → State)
- **Strategy** для обрезки контекста
- **Decorator** для статистики API (`StatsTrackingLLMApi`)
- **ISP**: `Agent` (read-only) → `ConfigurableAgent` (мутация) → `SimpleLLMAgent` (реализация)

---

## 🚫 Чего избегать

- Не добавлять Android-зависимости в `agent/`
- Не обращаться к `AgentContext` снаружи агента
- Не передавать историю в `AgentRequest` — агент управляет ею сам
- Не использовать `GlobalScope` — только `viewModelScope`
- Не блокировать main thread — IO на `Dispatchers.IO`
- Не использовать `runBlocking` в `suspend`-функциях
- Не включать `originalMessages` в LLM-запрос
- Не вызывать `updateConfig` / `updateTruncationStrategy` через `Agent` — только через `ConfigurableAgent`
- Не делать unsafe-cast `agent as ConfigurableAgent` — фабрики уже возвращают правильный тип
