# 🗜️ Компрессия истории (Summary)

## Принцип

```
История: [M1 … M15], keepRecentCount=5, summaryBlockSize=10

До:   [M1, M2, …, M10, M11, M12, M13, M14, M15]
После truncate():
  _context:        [M11, M12, M13, M14, M15]
  summaryStorage:  ConversationSummary(content="…", originalMessages=[M1…M10])

LLM-запрос:        [system: summary] + [M11…M15] + [новый вопрос]
UI:                [M1🗜️ … M10🗜️] + [M11…M15] + [новый вопрос]
```

> `originalMessages` → только UI. В LLM уходит только `content`.

## ConversationSummary

```kotlin
data class ConversationSummary(
    val content: String,                      // в LLM
    val originalMessages: List<AgentMessage>, // только UI
    val createdAt: Long = System.currentTimeMillis()
)
```

## ContextTruncationStrategy

```kotlin
interface ContextTruncationStrategy {
    suspend fun truncate(
        messages: List<AgentMessage>,
        maxTokens: Int?,
        maxMessages: Int?
    ): List<AgentMessage>

    // Дополнительные системные сообщения для LLM-запроса.
    // По умолчанию emptyList() — стратегии с компрессией переопределяют.
    // SimpleLLMAgent вызывает через интерфейс — без приведения типов.
    suspend fun getAdditionalSystemMessages(): List<AgentMessage> = emptyList()
}
```

## Стратегии обрезки

| Стратегия | Суть | `getAdditionalSystemMessages` |
|-----------|------|-------------------------------|
| `SimpleContextTruncationStrategy` | Удаляет старейшие сообщения | `emptyList()` (default) |
| `PreserveSystemTruncationStrategy` | Удаляет старейшие, сохраняет system | `emptyList()` (default) |
| `SummaryTruncationStrategy` | Сжимает старые в summary, оригиналы для UI | summary как `[system]` сообщение |

## TruncationUtils (композиция)

```kotlin
typealias TokenEstimator = (AgentMessage) -> Int

object TokenEstimators {
    val default: TokenEstimator = { (it.content.length / 4).coerceAtLeast(1) }
}

object TruncationUtils {
    fun truncateByTokens(
        messages: List<AgentMessage>,
        maxTokens: Int,
        estimator: TokenEstimator
    ): List<AgentMessage>
}
```

Все три стратегии используют `TruncationUtils.truncateByTokens` и `TokenEstimators.default`
через композицию — без наследования.

## SummaryTruncationStrategy

```kotlin
class SummaryTruncationStrategy(
    private val summaryProvider: SummaryProvider,
    private val summaryStorage: SummaryStorage,      // приватный, снаружи не виден
    private val keepRecentCount: Int = 10,
    private val summaryBlockSize: Int = 10,
    private val tokenEstimator: TokenEstimator = TokenEstimators.default
) : ContextTruncationStrategy {
    override suspend fun truncate(...)
    override suspend fun getAdditionalSystemMessages(): List<AgentMessage>  // summary → LLM

    // Для Agent.getSummaries() / Agent.loadSummaries()
    suspend fun getSummaries(): List<ConversationSummary>
    suspend fun loadSummaries(summaries: List<ConversationSummary>)
    suspend fun clearSummaries()
}
```

> Снаружи агента `SummaryTruncationStrategy` недоступна.
> ViewModel работает только через `agent.getSummaries()` и `agent.loadSummaries()`.

## SummaryStorage (приватный)

```kotlin
interface SummaryStorage {
    suspend fun getSummaries(): List<ConversationSummary>
    suspend fun addSummary(summary: ConversationSummary)
    suspend fun clear()
    suspend fun getSize(): Int
    suspend fun isEmpty(): Boolean
    suspend fun loadSummaries(summaries: List<ConversationSummary>)
}
```

| Реализация | Хранилище | Синхронизация |
|------------|-----------|---------------|
| `InMemorySummaryStorage` | RAM | `Mutex` |
| `JsonSummaryStorage` | `summaries.json` | `Mutex` + `Dispatchers.IO` |

## SummaryProvider

```kotlin
interface SummaryProvider {
    suspend fun summarize(messages: List<AgentMessage>): String
}
// LLMSummaryProvider — через LLM
// SimpleSummaryProvider — fallback без LLM
```

## Формат summaries.json

```json
{
  "version": 1,
  "summaries": [{
    "content": "User asked about X, assistant explained Y.",
    "originalMessages": [
      {"role": "USER", "content": "...", "timestamp": 0},
      {"role": "ASSISTANT", "content": "...", "timestamp": 1}
    ],
    "createdAt": 1234567900
  }]
}
```
