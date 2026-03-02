# 📡 Data Layer

## API

```kotlin
// data/Api.kt — низкоуровневый интерфейс
interface LLMApi {
    fun sendMessageStream(chatRequest: ChatRequest): Flow<StreamResult>
}

// agent/LLMApi.kt — интерфейс для агента (инверсия зависимости)
interface StatsLLMApi {
    fun sendMessageStream(chatRequest: ChatRequest): Flow<StatsStreamResult>
}
```

`OpenAIApi` — реализует `LLMApi` через OkHttp SSE.
`StatsTrackingLLMApi` — реализует `StatsLLMApi`, оборачивает `LLMApi`, добавляет `TokenStats` и `durationMs`.

> `StatsLLMApi` определён в `agent/`, реализован в `data/` — зависимость направлена внутрь. ✅

## Domain-модели

```kotlin
// Для API-запросов (domain/Models.kt)
data class ChatRequest(
    val messages: List<ApiMessage>,
    val model: String,
    val stop: List<String>? = null,
    val max_tokens: Long? = null,
    val temperature: Float? = null,
    val stream: Boolean = false,
    val stream_options: StreamOptions? = null
)
data class ApiMessage(val role: String, val content: String)

// Результаты стриминга
sealed class StreamResult {
    data class Content(val text: String) : StreamResult()
    data class TokenUsage(val usage: Usage) : StreamResult()
}
sealed class StatsStreamResult {
    data class Content(val text: String) : StatsStreamResult()
    data class Stats(val tokenStats: TokenStats, val durationMs: Long) : StatsStreamResult()
}

// Статистика
data class TokenStats(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val timeToFirstTokenMs: Long? = null
)

// UI-модель
data class Message(
    val id: String,
    val isUser: Boolean,
    val text: String,
    val isLoading: Boolean = false,
    val tokenStats: TokenStats? = null,
    val responseDurationMs: Long? = null,
    val isCompressed: Boolean = false
)
```

## Persistence

```kotlin
// data/persistence/ChatHistoryRepository.kt
interface ChatHistoryRepository {
    suspend fun saveSession(session: ChatSession)
    suspend fun loadSession(sessionId: String): ChatSession?
    suspend fun loadLatestSession(): ChatSession?
    suspend fun loadActiveSession(): ChatSession?
    suspend fun setActiveSession(sessionId: String)
    suspend fun deleteSession(sessionId: String)
    suspend fun getAllSessions(): List<ChatSession>
    suspend fun clearAll()
    suspend fun getActiveSessionId(): String?
}
```

`JsonChatHistoryRepository` → `chat_history.json`

### Модели persistence

```kotlin
data class ChatSession(
    val id: String,
    val messages: List<PersistedAgentMessage>,
    val createdAt: Long,
    val updatedAt: Long,
    val model: String? = null,
    val sessionStats: PersistedSessionStats? = null,
    val summaries: List<PersistedSummary> = emptyList()
)
```

`ChatHistoryMapper` — конвертеры `ChatSession` ↔ `AgentMessage` / `ConversationSummary`.

## JsonMemoryStorage — персистенция LayeredMemory

Три **отдельных** файла, каждый со своим `Mutex`:

| Файл | Слой | Очищается при `clearSession`? |
|------|------|-------------------------------|
| `memory_working.json` | WORKING | ✅ да |
| `memory_long_term.json` | LONG_TERM | ❌ нет (persist между сессиями) |
| `memory_compressed.json` | compressed UI | ✅ да |

Структура каждого файла:
```json
{
  "version": 1,
  "entries": [
    { "key": "current task", "value": "реализовать LayeredMemory", "layer": "WORKING", "updatedAt": 0 }
  ]
}
```

Compressed-файл:
```json
{
  "version": 1,
  "messages": [
    { "role": "user", "content": "Привет!", "timestamp": 0 }
  ]
}
```

Данные загружаются лениво при первом обращении и кэшируются. Три независимых `Mutex`
позволяют читать LONG_TERM и писать WORKING одновременно без лишних блокировок.

## Файлы данных

| Файл | Путь на устройстве | Содержимое |
|------|--------------------|------------|
| `chat_history.json` | `files/chat_history.json` | Сессии + сообщения + summaries |
| `summaries.json` | `files/summaries.json` | Summaries (кэш для быстрой загрузки) |
| `facts.json` | `files/facts.json` | Key-value факты (StickyFacts) |
| `branches.json` | `files/branches.json` | Ветки диалога (Branching) |
| `memory_working.json` | `files/memory_working.json` | Рабочая память (LayeredMemory) |
| `memory_long_term.json` | `files/memory_long_term.json` | Долговременная память (LayeredMemory) |
| `memory_compressed.json` | `files/memory_compressed.json` | Вытесненные сообщения для UI (LayeredMemory) |

## Конфигурация (local.properties)

```properties
OPENAI_API_KEY=sk-xxx
OPENAI_URL=https://api.openai.com/v1/chat/completions
OPENAI_MODELS=gpt-4,gpt-3.5-turbo
```
