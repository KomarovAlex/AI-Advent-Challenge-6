# 🎨 UI Layer

## AgentChatViewModel

```kotlin
class AgentChatViewModel(
    private val agent: Agent,
    private val availableModels: List<String>,
    private val chatHistoryRepository: ChatHistoryRepository? = null,
    initialStrategy: ContextStrategyType = ContextStrategyType.SUMMARY,
    private val strategyFactory: ((ContextStrategyType) -> ContextTruncationStrategy?)? = null
) : ViewModel()
```

### Сборка списка сообщений

```kotlin
// buildUiState() — декларативно, детали скрыты в AgentMessageUiMapper
val longTermMessages    = internal.longTermMemory.toLongTermMemoryUiMessage()   // 🧠 bubble
val workingMessages     = internal.workingMemory.toWorkingMemoryUiMessage()     // 💼 bubble
val memCompressed       = internal.memoryCompressedMessages.toMemoryCompressedUiMessages()
val compressedMessages  = internal.summaries.toCompressedUiMessages()
val factsMessages       = buildFactsMessage(internal.facts)                     // 📌 bubble
val factsCompressed     = internal.factsCompressedMessages.toFactsCompressedUiMessages()
val historyMessages     = agent.conversationHistory.toActiveUiMessages(...)
val streamingMessages   = listOfNotNull(internal.streamingMessage?.toUiMessage())

// Порядок сверху вниз (список перевёрнут в LazyColumn с reverseLayout=true):
allMessages = longTermMessages + workingMessages + memCompressed +
              factsMessages + factsCompressed + compressedMessages +
              historyMessages + streamingMessages
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
    // StickyFacts
    data object RefreshFacts
    // Branching
    data object CreateCheckpoint
    data object OpenBranchDialog
    data class SwitchBranch(val branchId: String)
    // Layered Memory
    data object RefreshWorkingMemory   // кнопка 💼 в тулбаре
    data object RefreshLongTermMemory  // кнопка 🧠 в тулбаре
    data object ClearAllMemory         // полная очистка памяти (включая LONG_TERM)
}
```

Добавить новый intent:
1. Добавить case в `sealed class ChatIntent`
2. Обработать в `handleIntent()` в ViewModel

### Capability accessors

```kotlin
private val summaryStrategy: SummaryTruncationStrategy?
    get() = agent.truncationStrategy as? SummaryTruncationStrategy

private val factsStrategy: StickyFactsStrategy?
    get() = agent.truncationStrategy as? StickyFactsStrategy

private val branchingStrategy: BranchingStrategy?
    get() = agent.truncationStrategy as? BranchingStrategy

private val layeredMemoryStrategy: LayeredMemoryStrategy?
    get() = agent.truncationStrategy as? LayeredMemoryStrategy
```

---

## AgentMessageUiMapper

**Файл:** `ui/AgentMessageUiMapper.kt`

Extension-функции для конвертации агентных моделей в UI-модели.
Живут в `ui/` — знают про `isCompressed`, `isLoading` и другие UI-концепты.

```kotlin
// Одно сообщение → UI
fun AgentMessage.toUiMessage(id, tokenStats?, responseDurationMs?, isCompressed): Message

// Summaries → сжатые UI-сообщения (isCompressed=true)
fun List<ConversationSummary>.toCompressedUiMessages(): List<Message>

// Compressed от StickyFacts → UI (isCompressed=true)
fun List<AgentMessage>.toFactsCompressedUiMessages(): List<Message>

// Compressed от LayeredMemory → UI (isCompressed=true)
fun List<AgentMessage>.toMemoryCompressedUiMessages(): List<Message>

// WORKING memory entries → специальный UI-Message для WorkingMemoryBubble
// Возвращает null если список пуст
fun List<MemoryEntry>.toWorkingMemoryUiMessage(): Message?

// LONG_TERM memory entries → специальный UI-Message для LongTermMemoryBubble
// Возвращает null если список пуст
fun List<MemoryEntry>.toLongTermMemoryUiMessage(): Message?

// Активная история → UI-сообщения
// Последнему ответу ассистента проставляются stats и duration
fun List<AgentMessage>.toActiveUiMessages(lastMessageStats?, lastMessageDuration?): List<Message>
```

---

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
    val compressedMessageCount: Int,
    val activeStrategy: ContextStrategyType,

    // StickyFacts
    val facts: List<Fact>,
    val isRefreshingFacts: Boolean,

    // Branching
    val branches: List<DialogBranch>,
    val activeBranchId: String?,
    val isBranchLimitReached: Boolean,
    val isSwitchingBranch: Boolean,
    val isBranchDialogOpen: Boolean,

    // Layered Memory
    val workingMemory: List<MemoryEntry>,           // текущая рабочая память
    val longTermMemory: List<MemoryEntry>,           // текущая долговременная память
    val memoryCompressedMessages: List<AgentMessage>, // вытесненные сообщения (только UI)
    val isRefreshingWorkingMemory: Boolean,
    val isRefreshingLongTermMemory: Boolean
)
```

---

## ContextStrategyType

```kotlin
enum class ContextStrategyType {
    SLIDING_WINDOW,   // Скользящее окно, нет компрессии
    STICKY_FACTS,     // Key-value факты + скользящее окно
    BRANCHING,        // Ветки диалога
    SUMMARY,          // LLM-суммаризация
    LAYERED_MEMORY    // Трёхслойная модель памяти: SHORT_TERM + WORKING + LONG_TERM
}
```

Отображаемые имена (в диалоге настроек):
```kotlin
fun ContextStrategyType.displayName(): String = when (this) {
    SLIDING_WINDOW -> "Sliding Window"
    STICKY_FACTS   -> "Sticky Facts"
    BRANCHING      -> "Branching"
    SUMMARY        -> "Summary (LLM)"
    LAYERED_MEMORY -> "Layered Memory 🧠"
}
```

---

## SettingsData

```kotlin
data class SettingsData(
    val model: String,
    val temperature: String?,
    val tokens: String?,
    val strategy: ContextStrategyType = ContextStrategyType.SUMMARY
)
```

Добавить новое поле в настройки:
1. Добавить в `SettingsData`
2. Добавить в `AgentConfig`
3. Обновить `Dialog.kt`

---

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
// alpha=0.5f, курсив, метка "🗜️ сжато"
```

### FactsBubble — факты StickyFacts
```kotlin
@Composable
fun FactsBubble(text)
// id = "facts_bubble", tertiaryContainer, метка "📌 Key facts"
```

### WorkingMemoryBubble — рабочая память 💼
```kotlin
@Composable
fun WorkingMemoryBubble(text)
// id = "working_memory_bubble", primaryContainer, метка "💼 Working memory"
```

### LongTermMemoryBubble — долговременная память 🧠
```kotlin
@Composable
fun LongTermMemoryBubble(text)
// id = "long_term_memory_bubble", tertiaryContainer, метка "🧠 Long-term memory"
```

Диспетчеризация в `MessageList`:
```kotlin
when {
    message.id == "long_term_memory_bubble" -> LongTermMemoryBubble(...)
    message.id == "working_memory_bubble"   -> WorkingMemoryBubble(...)
    message.id == "facts_bubble"            -> FactsBubble(...)
    message.isCompressed                    -> CompressedMessageBubble(...)
    else                                    -> MessageBubble(...)
}
```

---

## Тулбар: кнопки по стратегиям

| Стратегия | Кнопки |
|-----------|--------|
| `STICKY_FACTS` | ✨ `AutoAwesome` — Refresh Facts |
| `BRANCHING` | 🔖 `Bookmark` — Create Checkpoint, 🌿 `AccountTree` — Switch Branch |
| `LAYERED_MEMORY` | 💼 `Work` — Refresh Working Memory, 🧠 `Psychology` — Refresh Long-Term Memory |
| все | ⚙️ `Settings`, 🧹 `CleaningServices` — Clear Session |

---

## Persistence в ViewModel

**Загрузка** (`loadSavedHistory`):
1. `chatHistoryRepository.loadActiveSession()`
2. `summaryStrategy?.loadSummaries(savedSummaries)`
3. `agent.addToHistory(message)` для каждого сообщения
4. `factsStrategy?.getFacts()` — через capability
5. `layeredMemoryStrategy?.getWorkingMemory()` — через capability
6. `layeredMemoryStrategy?.getLongTermMemory()` — через capability (persist между сессиями)
7. `layeredMemoryStrategy?.getCompressedMessages()` — через capability

**Сохранение** (`saveHistory`):
1. `agent.conversationHistory` → `toSession()`
2. `summaryStrategy?.getSummaries()` → включается в сессию
3. `chatHistoryRepository.saveSession(session)`

> MemoryStorage сохраняет данные сам при каждом `replace*` — отдельного шага не нужно.

---

## Профили пользователя (ui/profile/)

### Экраны

#### ProfileListScreen
Список всех профилей. Показывает активный профиль (выделен `primaryContainer` + иконка ✅).
- **FAB** → создаёт новый `Profile()` и открывает `ProfileEditScreen` с его `id`
- Клик по карточке → `SelectProfile` + `onBack()`
- Кнопка «Edit» → `onNavigateToEdit(profile.id)`
- Кнопка удаления (только для не-default) → `RequestDelete` → диалог подтверждения

#### ProfileEditScreen
Редактор профиля. Поля: **name** (`OutlinedTextField`, отключено для default) и **rawText**.
Факты (`facts`) — read-only, отображаются как `AssistChip` во `FlowRow`.
- Загрузка: `LaunchedEffect(profileId)` → `ProfileEditIntent.Load`
- Сохранение: кнопка «Сохранить» → `ProfileEditIntent.Save` → после `isSaved == true` навигация назад

### MVI

#### ProfileListIntent / ProfileListState

```kotlin
sealed class ProfileListIntent {
    data object Load
    data class SelectProfile(val profileId: String)
    data class RequestDelete(val profileId: String)
    data object ConfirmDelete
    data object CancelDelete
    data class NavigateToEdit(val profileId: String)  // handled by screen
    data object CreateNew                              // handled by screen
}

data class ProfileListState(
    val profiles: List<Profile> = emptyList(),
    val selectedProfileId: String = Profile.DEFAULT_PROFILE_ID,
    val isLoading: Boolean = false,
    val error: String? = null,
    val pendingDeleteId: String? = null   // id профиля, ожидающего подтверждения удаления
)
```

#### ProfileEditIntent / ProfileEditState

```kotlin
sealed class ProfileEditIntent {
    data class Load(val profileId: String)
    data class UpdateName(val name: String)
    data class UpdateRawText(val rawText: String)
    data object Save
    data object ClearError
    data object ClearSaved
}

data class ProfileEditState(
    val profile: Profile = Profile(),
    val isLoading: Boolean = false,
    val isSaved: Boolean = false,   // true → экран закрывается и возвращается назад
    val error: String? = null
)
```

### ViewModels

```kotlin
class ProfileListViewModel(storage: JsonProfileStorage) : ViewModel()
class ProfileEditViewModel(storage: JsonProfileStorage) : ViewModel()
```

Оба получают один и тот же экземпляр `JsonProfileStorage` из `AppModule.profileStorage`.
Создаются через `AppModule.createProfileListViewModel()` / `AppModule.createProfileEditViewModel()`.
