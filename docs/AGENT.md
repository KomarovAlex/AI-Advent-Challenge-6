# 🤖 Agent Layer

## Интерфейс Agent

```kotlin
interface Agent {
    val config: AgentConfig
    val truncationStrategy: ContextTruncationStrategy?
    val conversationHistory: List<AgentMessage>  // read-only снимок активной истории

    // Ветки — в интерфейсе, т.к. агент синхронизирует с ними _context
    suspend fun initBranches()
    suspend fun createCheckpoint(): DialogBranch?
    suspend fun switchToBranch(branchId: String): Boolean
    suspend fun getActiveBranchId(): String?
    suspend fun getBranches(): List<DialogBranch>

    // Core
    suspend fun chat(request: AgentRequest): AgentResponse
    suspend fun chatStream(request: AgentRequest): Flow<AgentStreamEvent>
    suspend fun send(message: String): Flow<AgentStreamEvent>
    suspend fun clearHistory()               // очищает историю + делегирует clear() стратегии
    suspend fun addToHistory(message: AgentMessage)
    fun updateConfig(newConfig: AgentConfig)
    fun updateTruncationStrategy(strategy: ContextTruncationStrategy?)
}
```

### Доступ к возможностям стратегий (Capability pattern)

Summaries и Facts не входят в `Agent` — это нарушило бы ISP при каждом добавлении стратегии.
Вместо этого ViewModel обращается к стратегии напрямую через приведение типа:

```kotlin
// В AgentChatViewModel — capability accessors:
private val summaryStrategy: SummaryTruncationStrategy?
    get() = agent.truncationStrategy as? SummaryTruncationStrategy

private val factsStrategy: StickyFactsStrategy?
    get() = agent.truncationStrategy as? StickyFactsStrategy

private val branchingStrategy: BranchingStrategy?
    get() = agent.truncationStrategy as? BranchingStrategy

// Использование:
val summaries = summaryStrategy?.getSummaries() ?: emptyList()
val facts     = factsStrategy?.getFacts() ?: emptyList()
factsStrategy?.loadFacts(savedFacts)
summaryStrategy?.loadSummaries(savedSummaries)
```

**Правила:**
- `AgentContext` инкапсулирован — снаружи недоступен
- `conversationHistory` — только чтение, не включает сжатые сообщения
- Summaries/Facts доступны через `truncationStrategy as? XxxStrategy`
- Мутации истории — только через `addToHistory` / `clearHistory`
- Ветки — через методы `Agent` (требуют синхронизации `_context`)

---

## AgentRequest

```kotlin
data class AgentRequest(
    val userMessage: String,
    val systemPrompt: String? = null,  // переопределяет defaultSystemPrompt
    val model: String,
    val temperature: Float? = null,
    val maxTokens: Long? = null,       // ограничение длины ОТВЕТА
    val stopSequences: List<String>? = null
)
// История НЕ передаётся в запросе — агент управляет ею сам
```

---

## AgentConfig

```kotlin
data class AgentConfig(
    // Параметры LLM-запроса (передаются в каждый вызов API):
    val defaultModel: String,
    val defaultTemperature: Float? = null,
    val defaultMaxTokens: Long? = null,        // макс. токенов в ОТВЕТЕ
    val defaultSystemPrompt: String? = null,
    val defaultStopSequences: List<String>? = null,

    // Поведение агента (управляют историей):
    val keepConversationHistory: Boolean = true,
    val maxHistorySize: Int? = null,           // макс. сообщений в контексте
    val maxContextTokens: Int? = null          // макс. токенов в контексте
                                               // ≠ defaultMaxTokens (тот — длина ответа)
)
```

> ⚠️ `defaultMaxTokens` — ограничивает длину **ответа** LLM (тип `Long`).  
> `maxContextTokens` — ограничивает **контекст** истории (тип `Int`).  
> Разные типы, разная семантика — не перепутать.

---

## ContextTruncationStrategy — расширенный контракт

```kotlin
interface ContextTruncationStrategy {
    suspend fun truncate(messages, maxTokens, maxMessages): List<AgentMessage>

    // По умолчанию emptyList() — переопределяют Summary и Facts стратегии
    suspend fun getAdditionalSystemMessages(): List<AgentMessage> = emptyList()

    // По умолчанию no-op — переопределяют все стратегии с состоянием
    suspend fun clear() {}
}
```

`clearHistory()` агента больше не содержит `when (strategy is XxxStrategy)`:

```kotlin
// ✅ После рефакторинга — OCP соблюдён
override suspend fun clearHistory() {
    _context.clear()
    _truncationStrategy?.clear()   // делегирование — стратегия сама знает, что очищать
}

// ❌ Было до рефакторинга — нарушение OCP
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

---

## SimpleLLMAgent — buildMessageList

Порядок сообщений в запросе к LLM:

```
1. [system]    systemPrompt (если есть)
2. [system]    getAdditionalSystemMessages() от стратегии (summary / facts)
3a. keepConversationHistory=true  → _context.getHistory() (уже содержит userMessage)
3b. keepConversationHistory=false → только текущий userMessage
```

---

## AgentContext (приватный)

```kotlin
interface AgentContext {
    val size: Int
    val isEmpty: Boolean
    fun getHistory(): List<AgentMessage>
    fun addMessage(message: AgentMessage)
    fun addMessages(messages: List<AgentMessage>)
    fun removeLastMessage(): AgentMessage?
    fun removeLastMessages(count: Int): List<AgentMessage>
    fun clear()
    fun replaceHistory(messages: List<AgentMessage>)
    fun copy(): AgentContext
    // + getLastMessages, getMessagesByRole, getLastMessage, getLastMessageByRole
    // + addUserMessage, addAssistantMessage, addSystemMessage
}
```

`SimpleAgentContext` — реализация с `synchronized` на каждой операции (методы синхронные).

---

## AgentException

```kotlin
sealed class AgentException : Exception {
    class ApiError(message, statusCode?, cause?)
    class ConfigurationError(message)
    class ValidationError(message)
    class TimeoutError(message, cause?)
}
```

---

## Потокобезопасность SimpleLLMAgent

| Поле | Механизм | Почему |
|------|----------|--------|
| `_config` | `@Volatile` + `synchronized` в setter | Читается в suspend без блокировки, пишется редко |
| `_truncationStrategy` | `@Volatile` + `synchronized` в setter | То же |
| `_context` | `synchronized` внутри `SimpleAgentContext` | Методы синхронные, suspend-точек нет |
| Flow стриминг | `.map` + `.catch` | Нет вложенного collect |

```kotlin
// ✅ @Volatile — видимость без блокировки при чтении в suspend
@Volatile private var _config: AgentConfig = initialConfig

// ✅ synchronized только в не-suspend методах (приостановок не будет)
override fun updateConfig(newConfig: AgentConfig) {
    synchronized(this) { _config = newConfig }
}

// ✅ Snapshot конфига — единственное чтение для согласованности параметров
override suspend fun send(message: String): Flow<AgentStreamEvent> {
    val config = _config   // ← snapshot, дальше только config, не _config
    val request = AgentRequest(model = config.defaultModel, ...)
    return chatStream(request)
}

// ❌ synchronized в suspend — блокирует поток при приостановке корутины
override suspend fun bad() {
    synchronized(this) { withContext(IO) { ... } }  // НЕ ДЕЛАТЬ
}
```
