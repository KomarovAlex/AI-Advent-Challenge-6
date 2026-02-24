# 🏗️ Структура проекта aiChallenge

> Android-приложение для чата с LLM (Large Language Model) с поддержкой стриминга ответов

## 📁 Дерево файлов

```
app/src/main/java/ru/koalexse/aichallenge/
├── MainActivity.kt                 # Точка входа, Compose UI
├── agent/                          # 🤖 Слой агента (бизнес-логика LLM)
│   ├── Agent.kt                    # Интерфейс агента
│   ├── AgentModels.kt              # Модели данных агента
│   ├── AgentFactory.kt             # Фабрика и Builder для создания агентов
│   ├── SimpleLLMAgent.kt           # Основная реализация агента
│   └── context/                    # Управление контекстом диалога
│       ├── AgentContext.kt         # Интерфейс контекста
│       ├── SimpleAgentContext.kt   # Простая реализация контекста
│       └── strategy/               # Стратегии обрезки контекста
│           ├── ContextTruncationStrategy.kt
│           ├── SimpleContextTruncationStrategy.kt
│           └── PreserveSystemTruncationStrategy.kt
├── data/                           # 📡 Слой данных (API, persistence)
│   ├── Api.kt                      # LLMApi интерфейс + OpenAIApi реализация
│   ├── StatsTrackingLLMApi.kt      # Декоратор для сбора статистики
│   └── persistence/                # Сохранение истории чата
│       ├── ChatHistoryModels.kt    # Модели для сериализации
│       ├── ChatHistoryMapper.kt    # Конвертеры между моделями
│       ├── ChatHistoryRepository.kt # Интерфейс репозитория
│       └── JsonChatHistoryRepository.kt # JSON-реализация
├── domain/                         # 📦 Доменные модели
│   └── Models.kt                   # Message, TokenStats, ChatRequest и др.
├── di/                             # 💉 Dependency Injection
│   └── AppModule.kt                # Ручной DI модуль + AppContainer
└── ui/                             # 🎨 UI слой (Jetpack Compose)
    ├── AgentChatViewModel.kt       # ViewModel с MVI-подходом
    ├── ChatScreen.kt               # Главный экран чата
    ├── Dialog.kt                   # Диалоги (настройки)
    ├── state/
    │   └── ChatUiState.kt          # UI состояние + SettingsData
    └── theme/
        ├── Theme.kt
        ├── Color.kt
        └── Type.kt
```

---

## 🔑 Ключевые компоненты

### 1. Agent (Агент)

**Файл:** `agent/Agent.kt`

```kotlin
interface Agent {
    val config: AgentConfig           // Конфигурация агента
    val context: AgentContext         // Контекст диалога (история)
    val conversationHistory: List<AgentMessage>
    
    suspend fun chat(request: AgentRequest): AgentResponse    // Полный ответ
    fun chatStream(request: AgentRequest): Flow<AgentStreamEvent>  // Стриминг
    fun send(message: String): Flow<AgentStreamEvent>         // Упрощённый метод
    fun clearHistory()
    fun addToHistory(message: AgentMessage)
    fun updateConfig(newConfig: AgentConfig)
}
```

**Реализация:** `SimpleLLMAgent` — использует `StatsLLMApi` для запросов.

---

### 2. AgentContext (Контекст диалога)

**Файл:** `agent/context/AgentContext.kt`

Управляет историей сообщений с поддержкой:
- Ограничения размера истории (`maxHistorySize`)
- Стратегий обрезки (`ContextTruncationStrategy`)

```kotlin
interface AgentContext {
    fun getHistory(): List<AgentMessage>
    fun addMessage(message: AgentMessage)
    fun addUserMessage(content: String): AgentMessage
    fun addAssistantMessage(content: String): AgentMessage
    fun clear()
    // ...
}
```

---

### 3. Data Layer (Слой данных)

#### LLMApi
**Файл:** `data/Api.kt`

```kotlin
interface LLMApi {
    fun sendMessageStream(chatRequest: ChatRequest): Flow<StreamResult>
}

class OpenAIApi(apiKey: String, url: String) : LLMApi
```

#### StatsLLMApi
**Файл:** `data/StatsTrackingLLMApi.kt`

Декоратор, добавляющий статистику (время до первого токена, общую длительность):

```kotlin
interface StatsLLMApi {
    fun sendMessageStream(chatRequest: ChatRequest): Flow<StatsStreamResult>
}

class StatsTrackingLLMApi(delegate: LLMApi) : StatsLLMApi
```

#### ChatHistoryRepository
**Файл:** `data/persistence/ChatHistoryRepository.kt`

```kotlin
interface ChatHistoryRepository {
    suspend fun saveSession(session: ChatSession)
    suspend fun loadSession(sessionId: String): ChatSession?
    suspend fun loadActiveSession(): ChatSession?
    suspend fun loadLatestSession(): ChatSession?
    suspend fun getAllSessions(): List<ChatSession>
    suspend fun clearAll()
}
```

**Реализация:** `JsonChatHistoryRepository` — хранит в JSON-файле.

---

### 4. Domain Models (Доменные модели)

**Файл:** `domain/Models.kt`

```kotlin
// UI модель сообщения
data class Message(
    val id: String,
    val isUser: Boolean,
    val text: String,
    val isLoading: Boolean,
    val tokenStats: TokenStats?,
    val responseDurationMs: Long?
)

// Статистика токенов
data class TokenStats(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val timeToFirstTokenMs: Long?
)

// Запрос к API
data class ChatRequest(
    val messages: List<ApiMessage>,
    val model: String,
    val temperature: Float?,
    val max_tokens: Long?,
    val stream: Boolean
)

// Результаты стриминга
sealed class StreamResult {
    data class Content(val text: String) : StreamResult()
    data class TokenUsage(val usage: Usage) : StreamResult()
}

sealed class StatsStreamResult {
    data class Content(val text: String) : StatsStreamResult()
    data class Stats(val tokenStats: TokenStats, val durationMs: Long) : StatsStreamResult()
}
```

**Файл:** `agent/AgentModels.kt`

```kotlin
enum class Role { USER, ASSISTANT, SYSTEM }

data class AgentMessage(
    val role: Role,
    val content: String,
    val timestamp: Long
)

data class AgentConfig(
    val defaultModel: String,
    val defaultTemperature: Float?,
    val defaultMaxTokens: Long?,
    val defaultSystemPrompt: String?,
    val defaultStopSequences: List<String>?,
    val keepConversationHistory: Boolean,
    val maxHistorySize: Int?
)

sealed class AgentStreamEvent {
    data class ContentDelta(val text: String) : AgentStreamEvent()
    data class Completed(val tokenStats: TokenStats, val durationMs: Long) : AgentStreamEvent()
    data class Error(val exception: Throwable) : AgentStreamEvent()
}
```

---

### 5. UI Layer (Слой UI)

#### ViewModel
**Файл:** `ui/AgentChatViewModel.kt`

MVI-подход с `StateFlow`:

```kotlin
class AgentChatViewModel(
    private val agent: Agent,
    private val availableModels: List<String>,
    private val chatHistoryRepository: ChatHistoryRepository?
) : ViewModel() {
    
    val state: StateFlow<ChatUiState>
    
    fun handleIntent(intent: ChatIntent)
}

sealed class ChatIntent {
    data class UpdateInput(val text: String) : ChatIntent()
    data class SendMessage(val text: String) : ChatIntent()
    data object ClearError : ChatIntent()
    data object ClearSession : ChatIntent()
    data object OpenSettings : ChatIntent()
    data class SaveSettings(val settingsData: SettingsData) : ChatIntent()
}
```

#### UI State
**Файл:** `ui/state/ChatUiState.kt`

```kotlin
data class ChatUiState(
    val messages: List<Message>,
    val availableModels: List<String>,
    val settingsData: SettingsData,
    val currentInput: String,
    val isLoading: Boolean,
    val isSettingsOpen: Boolean,
    val error: String?
)

data class SettingsData(
    val model: String,
    val temperature: String?,
    val tokens: String?
)
```

---

### 6. Dependency Injection

**Файл:** `di/AppModule.kt`

```kotlin
class AppModule(
    private val context: Context,
    private val apiKey: String,
    private val baseUrl: String,
    private val availableModels: List<String>
) {
    val llmApi: LLMApi by lazy { OpenAIApi(apiKey, baseUrl) }
    val statsLLMApi: StatsLLMApi by lazy { StatsTrackingLLMApi(llmApi) }
    val agent: Agent by lazy { AgentFactory.createAgentWithStats(statsLLMApi, agentConfig) }
    val chatHistoryRepository: ChatHistoryRepository by lazy { JsonChatHistoryRepository(context) }
    
    fun createAgentChatViewModel(): AgentChatViewModel
}

object AppContainer {
    fun initialize(context: Context, apiKey: String, baseUrl: String, availableModels: List<String>): AppModule
}
```

---

## 🔄 Поток данных

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              UI Layer                                    │
│  ┌──────────────┐    ┌─────────────────────┐    ┌──────────────────┐   │
│  │ MainActivity │───▶│ AgentChatViewModel  │◀──▶│   ChatScreen     │   │
│  └──────────────┘    │   (MVI Intents)     │    │   (Compose)      │   │
│                      └──────────┬──────────┘    └──────────────────┘   │
└─────────────────────────────────┼───────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                            Agent Layer                                   │
│  ┌──────────────────┐    ┌─────────────────┐    ┌────────────────────┐ │
│  │   SimpleLLMAgent │◀──▶│  AgentContext   │◀──▶│ TruncationStrategy │ │
│  │  (Flow стриминг) │    │ (история чата)  │    │   (обрезка)        │ │
│  └────────┬─────────┘    └─────────────────┘    └────────────────────┘ │
└───────────┼─────────────────────────────────────────────────────────────┘
            │
            ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                            Data Layer                                    │
│  ┌────────────────────┐    ┌──────────────┐    ┌─────────────────────┐ │
│  │ StatsTrackingLLMApi│───▶│   OpenAIApi  │───▶│  HTTP (OkHttp)      │ │
│  │   (декоратор)      │    │  (SSE stream)│    │                     │ │
│  └────────────────────┘    └──────────────┘    └─────────────────────┘ │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                    Persistence                                   │   │
│  │  ┌─────────────────────┐    ┌──────────────────────────────┐   │   │
│  │  │ChatHistoryRepository│◀──▶│ JsonChatHistoryRepository    │   │   │
│  │  │    (interface)      │    │ (JSON файл в filesDir)       │   │   │
│  │  └─────────────────────┘    └──────────────────────────────┘   │   │
│  └─────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 📦 Зависимости

```kotlin
// UI
androidx.activity:activity-compose:1.12.4
androidx.compose.material3:material3
androidx.compose.material:material-icons-extended:1.7.8

// Network
com.squareup.okhttp3:okhttp:5.3.2
com.squareup.retrofit2:retrofit:3.0.0
com.squareup.retrofit2:converter-gson:3.0.0

// Lifecycle
androidx.lifecycle:lifecycle-runtime-ktx:2.10.0

// Other
com.mikepenz:multiplatform-markdown-renderer-m3:0.39.2
com.google.accompanist:accompanist-systemuicontroller:0.36.0
```

---

## 🚀 Быстрый старт

### 1. Инициализация в MainActivity

```kotlin
class MainActivity : ComponentActivity() {
    private val appModule by lazy {
        AppContainer.initialize(
            context = applicationContext,
            apiKey = BuildConfig.OPENAI_API_KEY,
            baseUrl = BuildConfig.OPENAI_URL,
            availableModels = BuildConfig.OPENAI_MODELS.split(",")
        )
    }
    
    private val viewModel by lazy {
        appModule.createAgentChatViewModel()
    }
}
```

### 2. Создание агента через Builder

```kotlin
val agent = buildAgent {
    withApi(statsLLMApi)
    model("gpt-4")
    temperature(0.7f)
    maxTokens(4096)
    systemPrompt("You are a helpful assistant.")
    keepHistory(true)
    maxHistorySize(100)
}
```

### 3. Отправка сообщения

```kotlin
agent.send("Привет!")
    .collect { event ->
        when (event) {
            is AgentStreamEvent.ContentDelta -> print(event.text)
            is AgentStreamEvent.Completed -> println("\nDone! Tokens: ${event.tokenStats}")
            is AgentStreamEvent.Error -> println("Error: ${event.exception}")
        }
    }
```

---

## 📝 Конфигурация

### BuildConfig (local.properties → build.gradle)

```properties
OPENAI_API_KEY=sk-xxx
OPENAI_URL=https://api.openai.com/v1/chat/completions
OPENAI_MODELS=gpt-4,gpt-3.5-turbo
```

### Файл истории чата

Путь: `/data/data/ru.koalexse.aichallenge/files/chat_history.json`

```json
{
  "version": 1,
  "sessions": [
    {
      "id": "uuid",
      "messages": [
        {"role": "USER", "content": "Привет", "timestamp": 1234567890},
        {"role": "ASSISTANT", "content": "Здравствуйте!", "timestamp": 1234567891}
      ],
      "createdAt": 1234567890,
      "updatedAt": 1234567891,
      "model": "gpt-4"
    }
  ],
  "activeSessionId": "uuid"
}
```

---

## 🧪 Тестирование

Агент не зависит от Android и может тестироваться в изоляции:

```kotlin
@Test
fun `agent should collect history`() = runTest {
    val mockApi = MockStatsLLMApi()
    val agent = SimpleLLMAgent(mockApi, AgentConfig(defaultModel = "test"))
    
    agent.send("Hello").collect()
    
    assertEquals(2, agent.conversationHistory.size)
    assertEquals(Role.USER, agent.conversationHistory[0].role)
    assertEquals(Role.ASSISTANT, agent.conversationHistory[1].role)
}
```
