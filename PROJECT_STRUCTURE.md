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
│   └── task/                       # Task State Machine (Planning mode)
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
│       ├── JsonTaskStateStorage.kt # Персистенция состояния задачи → task_state.json
│       └── profile/
│           ├── ProfileModels.kt    # Profile (id, name, rawText, facts, isDefault)
│           └── JsonProfileStorage.kt  # CRUD + выбор активного профиля → profiles.json
├── domain/
│   └── Models.kt                   # Message, TokenStats, ChatRequest, ApiMessage
├── di/
│   └── AppModule.kt                # AppModule, AppContainer
│                                   #   buildStrategy() — 5 стратегий контекста
│                                   #   createTaskStateMachineAgent() — Planning mode
│                                   #   createProfileListViewModel / createProfileEditViewModel
└── ui/
    ├── AgentChatViewModel.kt       # ViewModel(ConfigurableAgent, TaskStateMachineAgent?)
    │                               #   isPlanningMode управляется через SaveSettings
    │                               #   + StartTask / AdvancePhase / ResetTask / ClearTaskError
    ├── AgentMessageUiMapper.kt     # AgentMessage/ConversationSummary/MemoryEntry → Message
    ├── ChatScreen.kt               # MessageBubble, CompressedMessageBubble, FactsBubble,
    │                               #   WorkingMemoryBubble, LongTermMemoryBubble
    ├── ChatContent.kt              # Toolbar: кнопки ▶ Start Task / → Advance Phase
    │                               #   (только в Planning mode)
    │                               #   + StartTaskDialog, task error dialog
    ├── Dialog.kt                   # MultiFieldInputDialog:
    │                               #   модель → Planning mode Switch → стратегия (5 шт.)
    │                               #   → температура → макс. токены
    │                               #   BranchSwitchDialog, StartTaskDialog
    ├── state/
    │   └── ChatUiState.kt          # ChatUiState (+ isPlanningMode, taskState, ...),
    │                               #   ContextStrategyType (5 стратегий, без TSM),
    │                               #   SettingsData (+ isPlanningMode: Boolean),
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
| [docs/COMPRESSION.md](./docs/COMPRESSION.md) | Стратегии (все 5), TruncationUtils, SummaryStorage, Facts, Branches, LayeredMemory, Planning mode |
| [docs/DATA_LAYER.md](./docs/DATA_LAYER.md) | API, persistence, domain-модели, профили |
| [docs/UI_LAYER.md](./docs/UI_LAYER.md) | ViewModel, MVI, ChatUiState, AgentMessageUiMapper, диалог настроек, профили |
| [docs/RECIPES.md](./docs/RECIPES.md) | Быстрый старт, типичные задачи, тесты |

## 💾 Файлы данных на устройстве

| Файл | Содержимое | Очищается при ClearSession? |
|------|------------|-----------------------------|
| `chat_history.json` | Сессии + сообщения + summaries | ✅ да |
| `summaries.json` | Summaries (Summary-стратегия) | ✅ да |
| `facts.json` | Key-value факты (StickyFacts) | ✅ да |
| `branches.json` | Ветки диалога (Branching) | ✅ да |
| `memory_working.json` | Рабочая память (LayeredMemory) | ✅ да |
| `memory_long_term.json` | Долговременная память (LayeredMemory) | ❌ нет — persist навсегда |
| `memory_compressed.json` | Вытесненные сообщения для UI (LayeredMemory) | ✅ да |
| `task_state.json` | Состояние задачи (Planning mode) | ❌ нет — задача переживает паузы |
| `profiles.json` | Список профилей + id выбранного | ❌ нет |
