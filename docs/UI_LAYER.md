# 🎨 UI Layer

## AgentChatViewModel

```kotlin
class AgentChatViewModel(
    private val agent: Agent,
    private val availableModels: List<String>,
    private val chatHistoryRepository: ChatHistoryRepository? = null
) : ViewModel()
// SummaryStorage не передаётся — summaries доступны через agent.getSummaries()
```

### Сборка списка сообщений

```kotlin
allMessages =
    summaries.flatMap { it.originalMessages }  // isCompressed=true, не идут в LLM
    + agent.conversationHistory                // активная история
    + streamingMessage?                        // текущий стрим
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
2. `agent.loadSummaries(savedSummaries)` — summaries уходят в агент
3. `agent.addToHistory(message)` для каждого сообщения

**Сохранение** (`saveHistory`):
1. `agent.conversationHistory` → `toSession()`
2. `agent.getSummaries()` → включается в сессию
3. `chatHistoryRepository.saveSession(session)`
