# 🎨 UI Layer

## AgentChatViewModel

```kotlin
class AgentChatViewModel(
    private val agent: ConfigurableAgent,   // явно декларирует право на мутацию
    private val availableModels: List<String>,
    private val chatHistoryRepository: ChatHistoryRepository? = null,
    initialStrategy: ContextStrategyType = ContextStrategyType.SUMMARY,
    private val strategyFactory: ((ContextStrategyType) -> ContextTruncationStrategy?)? = null,
    private val taskStateMachineAgent: TaskStateMachineAgent? = null  // null = Planning mode недоступен
) : ViewModel()
```

`ConfigurableAgent` вместо `Agent` — явный контракт: ViewModel имеет право вызывать
`updateConfig` и `updateTruncationStrategy`. Потребители без права на мутацию
работают с базовым `Agent`.

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
    data class SaveSettings(val settingsData: SettingsData)  // Planning mode управляется здесь
    // StickyFacts
    data object RefreshFacts
    // Branching
    data object CreateCheckpoint
    data object OpenBranchDialog
    data class SwitchBranch(val branchId: String)
    // Layered Memory
    data object RefreshWorkingMemory
    data object RefreshLongTermMemory
    data object ClearAllMemory
    // Task State Machine actions (доступны только в Planning mode)
    data object OpenStartTaskDialog
    data object CloseStartTaskDialog
    data class StartTask(val phaseInvariants: List<PhaseInvariants>)
    data object AdvancePhase
    data object ResetTask
    data object ClearTaskError
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

### Активный агент

```kotlin
// Planning mode и стратегия контекста — независимые оси.
// TSM использует собственный innerAgent, не конфликтует с agent.
private val activeAgent: ConfigurableAgent
    get() = if (_internalState.value.isPlanningMode && taskStateMachineAgent != null)
        taskStateMachineAgent else agent
```

### Смена стратегии и Planning mode в рантайме

Оба управляются через единый `SaveSettings` → `handleSettingsUpdate()`:

```kotlin
private fun handleSettingsUpdate(settingsData: SettingsData) {
    val oldStrategy     = _internalState.value.activeStrategy
    val newStrategy     = settingsData.strategy
    val wasPlanningMode = _internalState.value.isPlanningMode
    val nowPlanningMode = settingsData.isPlanningMode   // читаем из SettingsData

    // Обновляем конфиг агента (модель, температура, токены)
    agent.updateConfig(...)

    _internalState.update {
        it.copy(
            settingsData   = settingsData,
            isSettingsOpen = false,
            activeStrategy = newStrategy,
            isPlanningMode = nowPlanningMode,
            taskValidationError = if (!nowPlanningMode) null else it.taskValidationError
        )
    }

    when {
        // Planning mode только что включён — загрузить состояние задачи
        !wasPlanningMode && nowPlanningMode ->
            viewModelScope.launch {
                val savedTaskState = taskStateMachineAgent?.getTaskState()
                _internalState.update { it.copy(taskState = savedTaskState) }
            }
        // Стратегия контекста изменилась — применить смену
        newStrategy != oldStrategy && strategyFactory != null ->
            viewModelScope.launch { applyStrategyChange(newStrategy) }
    }
}
```

---

## AgentMessageUiMapper

**Файл:** `ui/AgentMessageUiMapper.kt`

Extension-функции для конвертации агентных моделей в UI-модели.
Живут в `ui/` — знают про `isCompressed`, `isLoading` и другие UI-концепты.

```kotlin
fun AgentMessage.toUiMessage(id, tokenStats?, responseDurationMs?, isCompressed): Message
fun List<ConversationSummary>.toCompressedUiMessages(): List<Message>
fun List<AgentMessage>.toFactsCompressedUiMessages(): List<Message>
fun List<AgentMessage>.toMemoryCompressedUiMessages(): List<Message>
fun List<MemoryEntry>.toWorkingMemoryUiMessage(): Message?    // null если пуст
fun List<MemoryEntry>.toLongTermMemoryUiMessage(): Message?   // null если пуст
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

    /**
     * Режим планирования — использует TaskStateMachineAgent вместо обычного агента.
     * Независим от activeStrategy: включается поверх любой стратегии контекста.
     * Управляется через SettingsData.isPlanningMode в диалоге настроек.
     */
    val isPlanningMode: Boolean,

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
    val workingMemory: List<MemoryEntry>,
    val longTermMemory: List<MemoryEntry>,
    val memoryCompressedMessages: List<AgentMessage>,
    val isRefreshingWorkingMemory: Boolean,
    val isRefreshingLongTermMemory: Boolean,

    // Task State Machine (только когда isPlanningMode == true)
    val taskState: TaskState?,
    val isValidatingTask: Boolean,
    val taskValidationError: String?,
    val isAdvancingPhase: Boolean,
    val isStartTaskDialogOpen: Boolean
)
```

---

## ContextStrategyType

```kotlin
/**
 * Тип активной стратегии управления контекстом.
 *
 * Task State Machine намеренно отсутствует: это не стратегия обрезки контекста,
 * а отдельный режим работы агента. Включается через SettingsData.isPlanningMode.
 */
enum class ContextStrategyType {
    SLIDING_WINDOW,
    STICKY_FACTS,
    BRANCHING,
    SUMMARY,
    LAYERED_MEMORY
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
    val temperature: String? = null,
    val tokens: String? = null,
    val strategy: ContextStrategyType = ContextStrategyType.SUMMARY,
    val maxRetries: String? = null,
    /** Включён ли Planning mode (Task State Machine) */
    val isPlanningMode: Boolean = false
)
```

Добавить новое поле в настройки:
1. Добавить в `SettingsData`
2. Добавить в `AgentConfig` если нужно влиять на агента
3. Обновить `Dialog.kt` (добавить UI-поле в `MultiFieldInputDialog`)

---

## Диалог настроек — MultiFieldInputDialog

**Файл:** `ui/Dialog.kt`

Порядок полей в диалоге:
1. **Выбор модели** — `ExposedDropdownMenuBox`
2. **Planning mode 🤖** — `Switch` в строке с лейблом (`Row` + `SpaceBetween`)
3. **Context strategy** — `ExposedDropdownMenuBox` (5 стратегий, без TSM)
4. **Temperature** — `OutlinedTextField`
5. **Max tokens** — `OutlinedTextField`

Planning mode включается через этот Switch — не через тулбар.
Состояние хранится в `SettingsData.isPlanningMode` и передаётся в `SaveSettings`.

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

## Тулбар: кнопки по режимам

| Элемент | Условие показа | Действие |
|---------|---------------|----------|
| ▶ `PlayArrow` | `isPlanningMode && (!hasActiveTask \|\| isTaskDone)` | `OpenStartTaskDialog` |
| `Refresh` → Next Phase | `isPlanningMode && hasActiveTask && !isTaskDone` | `AdvancePhase` |
| ✨ `AutoAwesome` | `STICKY_FACTS` | `RefreshFacts` |
| 🔖 `Bookmark` | `BRANCHING` | `CreateCheckpoint` |
| 🌿 `AccountTree` | `BRANCHING` | `OpenBranchDialog` |
| 💼 `Work` | `LAYERED_MEMORY` | `RefreshWorkingMemory` |
| 🧠 `Psychology` | `LAYERED_MEMORY` | `RefreshLongTermMemory` |
| 👤 `Person` | все | Навигация к профилям |
| ⚙️ `Settings` | `SLIDING_WINDOW`, `SUMMARY` (как иконка), остальные — в overflow | `OpenSettings` |
| «Reset task» (overflow) | `isPlanningMode && hasActiveTask` | `ResetTask` |
| «Clear session» (overflow) | **всегда** | `ClearSession` |

> Planning mode включается через Switch в диалоге настроек — **не** в тулбаре.
> Кнопки управления задачей (▶ / →) появляются в тулбаре **после** включения режима.

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
8. Если `isPlanningMode`: `taskStateMachineAgent?.getTaskState()` — состояние задачи

**Сохранение** (`saveHistory`):
1. `agent.conversationHistory` → `toSession()`
2. `summaryStrategy?.getSummaries()` → включается в сессию
3. `chatHistoryRepository.saveSession(session)`

> TaskStateMachineAgent сохраняет своё состояние автоматически через `JsonTaskStateStorage`
> при каждом ответе — отдельного шага не нужно.
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
    val pendingDeleteId: String? = null
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
    val isSaved: Boolean = false,
    val error: String? = null
)
```

### ViewModels

```kotlin
class ProfileListViewModel(storage: JsonProfileStorage) : ViewModel()

class ProfileEditViewModel(
    storage: JsonProfileStorage,
    summaryProvider: SummaryProvider
) : ViewModel()
```

Оба получают один и тот же экземпляр `JsonProfileStorage` из `AppModule.profileStorage`.
Создаются через `AppModule.createProfileListViewModel()` / `AppModule.createProfileEditViewModel()`.
