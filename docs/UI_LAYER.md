# 🎨 UI Layer

## AgentChatViewModel

```kotlin
class AgentChatViewModel(
    private val agent: Agent,
    private val availableModels: List<String>,
    private val chatHistoryRepository: ChatHistoryRepository? = null
) : ViewModel()
```

### Сборка списка сообщений

```kotlin
// buildUiState() — декларативно, детали скрыты в AgentMessageUiMapper
val compressedMessages = internal.summaries.toCompressedUiMessages()
val historyMessages    = agent.conversationHistory.toActiveUiMessages(
    lastMessageStats   = internal.lastMessageStats,
    lastMessageDuration = internal.lastMessageDuration
)
val streamingMessages  = listOfNotNull(internal.streamingMessage?.toUiMessage())

allMessages = compressedMessages + historyMessages + streamingMessages
```

### MVI Intents

```kotlin
sealed class ChatIntent {
    data class UpdateInput(val text: String)
    data class SendMessage(val text: String)
    data object ClearError
    data object ClearSession
    data object OpenSettings
    data class SaveSettings(val settingsData: SettingsData)
}
```

Добавить новый intent:
1. Добавить case в `sealed class ChatIntent`
2. Обработать в `handleIntent()` в ViewModel

## AgentMessageUiMapper

**Файл:** `ui/AgentMessageUiMapper.kt`

Extension-функции для конвертации агентных моделей в UI-модели.
Живут в `ui/` — знают про `isCompressed`, `isLoading` и другие UI-концепты.

```kotlin
// Одно сообщение → UI
fun AgentMessage.toUiMessage(
    id: String,
    tokenStats: TokenStats? = null,
    responseDurationMs: Long? = null,
    isCompressed: Boolean = false
): Message

// Summaries → сжатые UI-сообщения (isCompressed=true)
fun List<ConversationSummary>.toCompressedUiMessages(): List<Message>

// Активная история → UI-сообщения
// Последнему ответу ассистента проставляются stats и duration
fun List<AgentMessage>.toActiveUiMessages(
    lastMessageStats: TokenStats? = null,
    lastMessageDuration: Long? = null
): List<Message>
```

## ChatUiState

```kotlin
data class ChatUiState(
    val messages: List<Message>,
    val availableModels: List<String>,
    val settingsData: SettingsData,
    val currentInput: String,
    val isLoading: Boolean,
    val isSettingsOpen: Boolean,
    val error: String?,
    val sessionStats: SessionTokenStats?,
    val compressedMessageCount: Int
)
```

## SettingsData

```kotlin
data class SettingsData(
    val model: String,
    val temperature: String?,
    val tokens: String?
)
```

Добавить новое поле в настройки:
1. Добавить в `SettingsData`
2. Добавить в `AgentConfig`
3. Обновить `Dialog.kt`

## Composable-компоненты

### MessageBubble — обычное сообщение
```kotlin
@Composable
fun MessageBubble(isUser, text, isLoading, tokenStats, responseDurationMs)
```

### CompressedMessageBubble — сжатое сообщение
```kotlin
@Composable
fun CompressedMessageBubble(isUser, text)
// alpha=0.5f, курсив, метка "🗜️ сжато", RoundedCornerShape(8.dp)
```

Выбор в `MessageList`:
```kotlin
if (message.isCompressed) CompressedMessageBubble(...) else MessageBubble(...)
```

## Persistence в ViewModel

**Загрузка** (`loadSavedHistory`):
1. `chatHistoryRepository.loadActiveSession()`
2. `agent.loadSummaries(savedSummaries)`
3. `agent.addToHistory(message)` для каждого сообщения

**Сохранение** (`saveHistory`):
1. `agent.conversationHistory` → `toSession()`
2. `agent.getSummaries()` → включается в сессию
3. `chatHistoryRepository.saveSession(session)`
