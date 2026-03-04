# 🏗️ Структура проекта aiChallenge

> Детальная документация: [docs/](./docs/)

## 📁 Дерево файлов

```
app/src/main/java/ru/koalexse/aichallenge/
├── MainActivity.kt
├── agent/
│   ├── Agent.kt                    # Read-only интерфейс агента (history, chat, branches)
│   ├── ConfigurableAgent.kt        # Мутирующее расширение Agent (updateConfig, updateTruncationStrategy)
│   ├── AgentModels.kt              # AgentRequest, AgentConfig, AgentMessage, AgentStreamEvent
│   ├── AgentFactory.kt             # AgentFactory, AgentBuilder, buildAgent {} → ConfigurableAgent
│   ├── LLMApi.kt                   # interface StatsLLMApi (реализация в data/)
│   ├── SimpleLLMAgent.kt           # Реализация ConfigurableAgent
│   └── context/
│       ├── AgentContext.kt         # Интерфейс хранилища (приватный для агента)
│       ├── SimpleAgentContext.kt
│       ├── strategy/
│       │   ├── ContextTruncationStrategy.kt      # + getAdditionalSystemMessages()
│       │   ├── TruncationUtils.kt                # TokenEstimator, TokenEstimators, TruncationUtils
│       │   ├── SimpleContextTruncationStrategy.kt
│       │   ├── PreserveSystemTruncationStrategy.kt
│       │   ├── SummaryTruncationStrategy.kt
│       │   ├── SlidingWindowStrategy.kt          # Стратегия 1: последние N сообщений
│       │   ├── StickyFactsStrategy.kt            # Стратегия 2: key-value факты + N сообщений
│       │   ├── BranchingStrategy.kt              # Стратегия 3: ветки диалога
│       │   └── LayeredMemoryStrategy.kt          # Стратегия 5: трёхслойная модель памяти
│       ├── summary/
│       │   ├── SummaryModels.kt    # ConversationSummary
│       │   ├── SummaryStorage.kt   # + InMemorySummaryStorage
│       │   ├── JsonSummaryStorage.kt
│       │   ├── SummaryProvider.kt
│       │   └── LLMSummaryProvider.kt
│       ├── facts/
│       │   ├── FactsModels.kt      # Fact (key, value, updatedAt)
│       │   └── FactsStorage.kt     # interface + InMemoryFactsStorage
│       ├── branch/
│       │   ├── BranchModels.kt     # DialogBranch (id, name, messages, summaries)
│       │   └── BranchStorage.kt    # interface + InMemoryBranchStorage
│       └── memory/
│           ├── MemoryModels.kt     # MemoryEntry, MemoryLayer, LayeredMemorySnapshot
│           └── MemoryStorage.kt    # interface + InMemoryMemoryStorage
│   └── task/                       # ← НОВОЕ: Task State Machine
│       ├── TaskModels.kt           # TaskPhase, TaskState, PhaseInvariants, ArchivedTask,
│       │                           #   TaskSignal, ValidationResult
│       ├── TaskStateStorage.kt     # interface + InMemoryTaskStateStorage
│       └── TaskStateMachineAgent.kt # Агент-обёртка: автомат + валидация + персистентность
│                                    #   AdvancePhaseResult, fun TaskPhase.next()
├── data/
│   ├── Api.kt                      # interface LLMApi + class OpenAIApi
│   ├── StatsTrackingLLMApi.kt      # реализует agent.StatsLLMApi
│   └── persistence/
│       ├── ChatHistoryModels.kt
│       ├── ChatHistoryMapper.kt
│       ├── ChatHistoryRepository.kt
│       ├── JsonChatHistoryRepository.kt
│       ├── JsonFactsStorage.kt     # Персистенция фактов → facts.json
│       ├── JsonBranchStorage.kt    # Персистенция веток → branches.json
│       ├── JsonMemoryStorage.kt    # Персистенция памяти → memory_working.json,
│       │                           #   memory_long_term.json, memory_compressed.json
│       ├── JsonTaskStateStorage.kt # ← НОВОЕ: Персистенция состояния задачи → task_state.json
│       └── profile/
│           ├── ProfileModels.kt    # Profile (id, name, rawText, facts, isDefault)
│           └── JsonProfileStorage.kt  # CRUD + выбор активного профиля → profiles.json
├── domain/
│   └── Models.kt                   # Message, TokenStats, ChatRequest, ApiMessage
├── di/
│   └── AppModule.kt                # AppModule, AppContainer (6 стратегий в buildStrategy)
│                                   #   + createTaskStateMachineAgent()
│                                   #   + createProfileListViewModel / createProfileEditViewModel
└── ui/
    ├── AgentChatViewModel.kt       # ViewModel(ConfigurableAgent, TaskStateMachineAgent?)
    │                               #   + StartTask / AdvancePhase / ResetTask / ClearTaskError
    ├── AgentMessageUiMapper.kt     # AgentMessage/ConversationSummary/MemoryEntry → Message
    ├── ChatScreen.kt               # MessageBubble, CompressedMessageBubble, FactsBubble,
    │                               #   WorkingMemoryBubble, LongTermMemoryBubble
    ├── ChatContent.kt              # Toolbar: кнопки ▶ Start Task / → Advance Phase (TASK_STATE_MACHINE)
    │                               #   + StartTaskDialog, task error dialog
    ├── Dialog.kt                   # MultiFieldInputDialog (+ maxRetries для TSM),
    │                               #   BranchSwitchDialog, StartTaskDialog ← НОВОЕ
    ├── state/
    │   └── ChatUiState.kt          # ChatUiState (+ taskState, isValidatingTask, ...),
    │                               #   ContextStrategyType (+ TASK_STATE_MACHINE),
    │                               #   SettingsData (+ maxRetries),
    │                               #   fun TaskPhase.displayName()
    └── profile/
        ├── ProfileModels.kt        # ProfileListState, ProfileEditState
        ├── ProfileViewModel.kt     # ProfileListViewModel + ProfileListIntent (MVI)
        ├── ProfileEditViewModel.kt # ProfileEditViewModel + ProfileEditIntent (MVI)
        ├── ProfileListScreen.kt    # Список профилей, выбор активного, удаление
        └── ProfileEditScreen.kt    # Редактор профиля: имя, rawText, просмотр фактов
```

## 📚 Документация

| Файл | Содержимое |
|------|------------|
| [docs/ARCHITECTURE.md](./docs/ARCHITECTURE.md) | Схемы, поток данных, разделение ответственности |
| [docs/AGENT.md](./docs/AGENT.md) | Agent, ConfigurableAgent, SimpleLLMAgent, AgentContext, buildMessageList |
| [docs/COMPRESSION.md](./docs/COMPRESSION.md) | Стратегии (все 6), TruncationUtils, SummaryStorage, Facts, Branches, LayeredMemory, TaskStateMachine |
| [docs/DATA_LAYER.md](./docs/DATA_LAYER.md) | API, persistence, domain-модели, профили |
| [docs/UI_LAYER.md](./docs/UI_LAYER.md) | ViewModel, MVI, ChatUiState, AgentMessageUiMapper, профили |
| [docs/RECIPES.md](./docs/RECIPES.md) | Быстрый старт, типичные задачи, тесты |

## 💾 Файлы данных на устройстве

| Файл | Содержимое |
|------|------------|
| `chat_history.json` | Сессии + сообщения + summaries |
| `summaries.json` | Summaries (кэш, Summary-стратегия) |
| `facts.json` | Key-value факты (StickyFacts-стратегия) |
| `branches.json` | Ветки диалога (Branching-стратегия) |
| `memory_working.json` | Рабочая память — текущая задача, шаги, результаты (LayeredMemory) |
| `memory_long_term.json` | Долговременная память — профиль, решения, знания (LayeredMemory) |
| `memory_compressed.json` | Сообщения, вытесненные из LLM-контекста (только UI, LayeredMemory) |
| `task_state.json` | Состояние задачи — фаза, шаг, инварианты, архив завершённых (TaskStateMachine) |
| `profiles.json` | Список профилей пользователя + id выбранного профиля |
```
