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
ViewModel обращается к стратегии напрямую через приведение типа:

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

`clearHistory()` агента не содержит `when (strategy is XxxStrategy)` — OCP соблюдён:

```kotlin
// ✅ Делегирование — стратегия сама знает, что очищать
override suspend fun clearHistory() {
    _context.clear()
    _truncationStrategy?.clear()
}
```

---

## SimpleLLMAgent — порядок операций в chat/chatStream

### Инварианты (выполняются в обоих методах)

**1. Валидация — первой, до мутации `_context`**

```kotlin
// ✅ validateRequest вызывается ДО addMessageWithTruncation
override suspend fun chat(request: AgentRequest): AgentResponse {
    validateRequest(request)   // ← если бросит — история не будет изменена
    val config = _config
    if (config.keepConversationHistory) {
        addMessageWithTruncation(AgentMessage(USER, request.userMessage), config)
    }
    ...
}

// ❌ Было: история менялась до валидации — при ValidationError оставался
//    user-message без ответа ассистента (corrupt history)
override suspend fun chat(request: AgentRequest): AgentResponse {
    addMessageWithTruncation(...)   // история уже изменена
    validateRequest(request)        // если бросит — история corrupt
    ...
}
```

**2. Snapshot конфига — одно volatile-чтение на весь вызов**

```kotlin
// ✅ val config = _config — единственное volatile-чтение
// Все дальнейшие обращения через локальный config, включая замыкание в .map { }
override suspend fun chatStream(request: AgentRequest): Flow<AgentStreamEvent> {
    validateRequest(request)
    val config = _config                          // ← snapshot
    if (config.keepConversationHistory) { ... }  // читаем config, не _config
    val chatRequest = buildChatRequest(request, config)
    return api.sendMessageStream(chatRequest)
        .map { result ->
            when (result) {
                is Stats -> {
                    if (config.keepConversationHistory ...) { // тот же snapshot
                        addMessageWithTruncation(..., config)
                    }
                }
            }
        }
}

// ❌ Было: несколько независимых чтений _config — при конкурентном updateConfig()
//    запрос мог уйти с частично старым, частично новым конфигом
```

**3. `config` передаётся явно в приватные методы — нет скрытых чтений `_config`**

```kotlin
private suspend fun buildChatRequest(request: AgentRequest, config: AgentConfig): ChatRequest
private suspend fun buildMessageList(request: AgentRequest, config: AgentConfig): List<ApiMessage>
private suspend fun addMessageWithTruncation(message: AgentMessage, config: AgentConfig)
private suspend fun applyTruncation(config: AgentConfig)
```

---

## SimpleLLMAgent — buildMessageList

Порядок сообщений в запросе к LLM:

```
1. [system]    systemPrompt из request или config.defaultSystemPrompt
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

| Поле / место | Механизм | Гарантия |
|---|---|---|
| `_config` (чтение) | `@Volatile` | Видимость последней записи |
| `_config` (запись) | `synchronized` в `updateConfig` | Атомарная замена ссылки |
| `_config` (использование) | `val config = _config` snapshot в начале метода | Согласованность параметров внутри одного вызова |
| `_truncationStrategy` | то же, что `_config` | то же |
| `_context` | `synchronized` внутри `SimpleAgentContext` | Потокобезопасные операции над списком |
| Flow стриминг | `.map` + `.catch` | Нет вложенного collect |

```kotlin
// ✅ @Volatile — видимость без блокировки при чтении в suspend
@Volatile private var _config: AgentConfig = initialConfig

// ✅ synchronized только в не-suspend методах (приостановок нет)
override fun updateConfig(newConfig: AgentConfig) {
    synchronized(this) { _config = newConfig }
}

// ✅ Snapshot — одно volatile-чтение, все обращения через локальную переменную
override suspend fun chat(request: AgentRequest): AgentResponse {
    validateRequest(request)   // сначала валидация
    val config = _config       // потом snapshot
    // дальше только config.xxx, никогда _config.xxx
}

// ✅ Snapshot передаётся в приватные методы явно
private suspend fun buildChatRequest(request: AgentRequest, config: AgentConfig): ChatRequest
private suspend fun buildMessageList(request: AgentRequest, config: AgentConfig): List<ApiMessage>

// ❌ synchronized в suspend — блокирует поток при приостановке корутины
suspend fun bad() { synchronized(lock) { withContext(IO) { } } }

// ✅ Mutex приостанавливает корутину, поток свободен
suspend fun good() { mutex.withLock { withContext(IO) { } } }
```
