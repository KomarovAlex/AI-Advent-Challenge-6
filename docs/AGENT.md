# 🤖 Agent Layer

## Интерфейс Agent

```kotlin
interface Agent {
    val config: AgentConfig
    val truncationStrategy: ContextTruncationStrategy?
    val conversationHistory: List<AgentMessage>  // read-only снимок активной истории

    // Summaries инкапсулированы в агенте — ViewModel работает только через эти методы
    suspend fun getSummaries(): List<ConversationSummary>
    suspend fun loadSummaries(summaries: List<ConversationSummary>)

    suspend fun chat(request: AgentRequest): AgentResponse
    suspend fun chatStream(request: AgentRequest): Flow<AgentStreamEvent>
    suspend fun send(message: String): Flow<AgentStreamEvent>
    suspend fun clearHistory()               // очищает историю + summaries
    suspend fun addToHistory(message: AgentMessage)
    fun updateConfig(newConfig: AgentConfig)
    fun updateTruncationStrategy(strategy: ContextTruncationStrategy?)
}
```

**Правила:**
- `AgentContext` и `SummaryStorage` инкапсулированы — снаружи недоступны
- `conversationHistory` — только чтение, не включает сжатые сообщения
- Summaries доступны только через `getSummaries()` / `loadSummaries()`
- Мутации истории — только через `addToHistory` / `clearHistory`

## AgentRequest

```kotlin
data class AgentRequest(
    val userMessage: String,
    val systemPrompt: String? = null,  // переопределяет defaultSystemPrompt
    val model: String,
    val temperature: Float? = null,
    val maxTokens: Long? = null,
    val stopSequences: List<String>? = null
)
// История НЕ передаётся в запросе — агент управляет ею сам
```

## AgentConfig

```kotlin
data class AgentConfig(
    val defaultModel: String,
    val defaultTemperature: Float? = null,
    val defaultMaxTokens: Long? = null,
    val defaultSystemPrompt: String? = null,
    val defaultStopSequences: List<String>? = null,
    val keepConversationHistory: Boolean = true,
    val maxHistorySize: Int? = null,
    val maxTokens: Int? = null
)
```

## SimpleLLMAgent — buildMessageList

Порядок сообщений в запросе к LLM:

```
1. [system]    systemPrompt (если есть)
2. [system]    "Previous conversation summary: …"  ← только content, не originalMessages
3a. keepConversationHistory=true  → _context.getHistory() (уже содержит userMessage)
3b. keepConversationHistory=false → только текущий userMessage
```

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

`SimpleAgentContext` — реализация с `synchronized` на каждой операции.

## AgentException

```kotlin
sealed class AgentException : Exception {
    class ApiError(message, statusCode?, cause?)
    class ConfigurationError(message)
    class ValidationError(message)
    class TimeoutError(message, cause?)
}
```

## Потокобезопасность SimpleLLMAgent

| Поле | Механизм |
|------|----------|
| `_config`, `_truncationStrategy` | `synchronized(this)` |
| `_context` | `synchronized` внутри `SimpleAgentContext` |
| Flow стриминг | `.map` + `.catch` — нет вложенного collect |
