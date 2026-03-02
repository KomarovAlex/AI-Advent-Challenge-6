# 🏗️ Структура проекта aiChallenge

> Детальная документация: [docs/](./docs/)

## 📁 Дерево файлов

```
app/src/main/java/ru/koalexse/aichallenge/
├── MainActivity.kt
├── agent/
│   ├── Agent.kt                    # Интерфейс агента (+ facts/branches API)
│   ├── AgentModels.kt              # AgentRequest, AgentConfig, AgentMessage, AgentStreamEvent
│   ├── AgentFactory.kt             # AgentFactory, AgentBuilder, buildAgent {}
│   ├── LLMApi.kt                   # interface StatsLLMApi (реализация в data/)
│   ├── SimpleLLMAgent.kt           # Реализация агента
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
│       └── JsonMemoryStorage.kt    # Персистенция памяти → memory_working.json,
│                                   #   memory_long_term.json, memory_compressed.json
├── domain/
│   └── Models.kt                   # Message, TokenStats, ChatRequest, ApiMessage
├── di/
│   └── AppModule.kt                # AppModule, AppContainer (5 стратегий в buildStrategy)
└── ui/
    ├── AgentChatViewModel.kt       # ViewModel + ChatIntent (+ RefreshWorking/LongTerm Memory)
    ├── AgentMessageUiMapper.kt     # AgentMessage/ConversationSummary/MemoryEntry → Message
    ├── ChatScreen.kt               # MessageBubble, CompressedMessageBubble, FactsBubble,
    │                               #   WorkingMemoryBubble, LongTermMemoryBubble
    ├── Dialog.kt                   # MultiFieldInputDialog (+ стратегия), BranchSwitchDialog
    └── state/
        └── ChatUiState.kt          # ChatUiState, SettingsData, ContextStrategyType (+ LAYERED_MEMORY)
```

## 📚 Документация

| Файл | Содержимое |
|------|------------|
| [docs/ARCHITECTURE.md](./docs/ARCHITECTURE.md) | Схемы, поток данных, разделение ответственности |
| [docs/AGENT.md](./docs/AGENT.md) | Agent, SimpleLLMAgent, AgentContext, buildMessageList |
| [docs/COMPRESSION.md](./docs/COMPRESSION.md) | Стратегии (все 5), TruncationUtils, SummaryStorage, Facts, Branches, LayeredMemory |
| [docs/DATA_LAYER.md](./docs/DATA_LAYER.md) | API, persistence, domain-модели |
| [docs/UI_LAYER.md](./docs/UI_LAYER.md) | ViewModel, MVI, ChatUiState, AgentMessageUiMapper |
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
