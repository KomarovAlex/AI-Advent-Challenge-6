# 🏗️ Структура проекта aiChallenge

> Детальная документация: [docs/](./docs/)

## 📁 Дерево файлов

```
app/src/main/java/ru/koalexse/aichallenge/
├── MainActivity.kt
├── agent/
│   ├── Agent.kt                    # Интерфейс агента
│   ├── AgentModels.kt              # AgentRequest, AgentConfig, AgentMessage, AgentStreamEvent
│   ├── AgentFactory.kt             # AgentFactory, AgentBuilder, buildAgent {}
│   ├── LLMApi.kt                   # interface StatsLLMApi (реализация в data/)
│   ├── SimpleLLMAgent.kt           # Реализация агента
│   └── context/
│       ├── AgentContext.kt         # Интерфейс хранилища (приватный для агента)
│       ├── SimpleAgentContext.kt
│       ├── strategy/
│       │   ├── ContextTruncationStrategy.kt
│       │   ├── SimpleContextTruncationStrategy.kt
│       │   ├── PreserveSystemTruncationStrategy.kt
│       │   └── SummaryTruncationStrategy.kt
│       └── summary/
│           ├── SummaryModels.kt    # ConversationSummary
│           ├── SummaryStorage.kt   # + InMemorySummaryStorage
│           ├── JsonSummaryStorage.kt
│           ├── SummaryProvider.kt
│           └── LLMSummaryProvider.kt
├── data/
│   ├── Api.kt                      # interface LLMApi + class OpenAIApi
│   ├── StatsTrackingLLMApi.kt      # реализует agent.StatsLLMApi
│   └── persistence/
│       ├── ChatHistoryModels.kt
│       ├── ChatHistoryMapper.kt
│       ├── ChatHistoryRepository.kt
│       └── JsonChatHistoryRepository.kt
├── domain/
│   └── Models.kt                   # Message, TokenStats, ChatRequest, ApiMessage
├── di/
│   └── AppModule.kt                # AppModule, AppContainer
└── ui/
    ├── AgentChatViewModel.kt       # ViewModel + ChatIntent
    ├── ChatScreen.kt               # MessageBubble, CompressedMessageBubble
    ├── Dialog.kt
    └── state/
        └── ChatUiState.kt          # ChatUiState, SettingsData
```

## 📚 Документация

| Файл | Содержимое |
|------|------------|
| [docs/ARCHITECTURE.md](./docs/ARCHITECTURE.md) | Схемы, поток данных, разделение ответственности |
| [docs/AGENT.md](./docs/AGENT.md) | Agent, SimpleLLMAgent, AgentContext, buildMessageList |
| [docs/COMPRESSION.md](./docs/COMPRESSION.md) | Summary-компрессия, SummaryStorage, стратегии |
| [docs/DATA_LAYER.md](./docs/DATA_LAYER.md) | API, persistence, domain-модели |
| [docs/UI_LAYER.md](./docs/UI_LAYER.md) | ViewModel, MVI, ChatUiState, Composable |
| [docs/RECIPES.md](./docs/RECIPES.md) | Быстрый старт, типичные задачи, тесты |
