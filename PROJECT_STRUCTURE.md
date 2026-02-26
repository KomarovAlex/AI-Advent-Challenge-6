# 🏗️ Структура проекта aiChallenge

> Android-приложение для чата с LLM (Large Language Model) с поддержкой стриминга ответов и компрессии истории

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
│       ├── AgentContext.kt         # Интерфейс контекста (простое хранилище)
│       ├── SimpleAgentContext.kt   # Простая реализация контекста
│       ├── strategy/               # Стратегии обрезки контекста
│       │   ├── ContextTruncationStrategy.kt      # Интерфейс (suspend)
│       │   ├── SimpleContextTruncationStrategy.kt
│       │   ├── PreserveSystemTruncationStrategy.kt
│       │   └── SummaryTruncationStrategy.kt      # Компрессия через summary
│       └── summary/                # Компрессия истории
│           ├── SummaryModels.kt    # ConversationSummary
│           ├── SummaryStorage.kt   # Интерфейс (suspend) + InMemorySummaryStorage
│           ├── JsonSummaryStorage.kt # JSON-реализация с persistence
│           ├── SummaryProvider.kt  # Интерфейс генерации summary
│           └── LLMSummaryProvider.kt # Реализация через LLM
├── data/                           # 📡 Слой данных (API, persistence)
│   ├── Api.kt                      # LLMApi интерфейс + OpenAIApi реализация
│   ├── StatsTrackingLLMApi.kt      # Декоратор для сбора статистики
│   └── persistence/                # Сохранение истории чата
│       ├── ChatHistoryModels.kt    # Модели для сериализации (+ summaries)
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
    val config: AgentConfig                         // Конфигурация агента
    val context: AgentContext                       // Контекст диалога (история)
    val truncationStrategy: ContextTruncationStrategy?  // Стратегия обрезки
    val conversationHistory: List<AgentMessage>
    
    suspend fun chat(request: AgentRequest): AgentResponse    // Полный ответ
    fun chatStream(request: AgentRequest): Flow<AgentStreamEvent>  // Стриминг
    fun send(message: String): Flow<AgentStreamEvent>         // Упрощённый метод
    fun clearHistory()
    suspend fun addToHistory(message: AgentMessage)
    fun updateConfig(newConfig: AgentConfig)
    fun updateTruncationStrategy(strategy: ContextTruncationStrategy?)
}
```

**Реализация:** `SimpleLLMAgent` — использует `StatsLLMApi` для запросов, применяет стратегию обрезки после каждого добавления сообщения.

**Важно:** Стриминг реализован через `channelFlow` для избежания deadlock при collect + emit.

---

### 2. AgentContext (Контекст диалога)

**Файл:** `agent/context/AgentContext.kt`

Простое хранилище сообщений. Стратегия обрезки вынесена в Agent.

```kotlin
interface AgentContext {
    val size: Int
    val isEmpty: Boolean
    
    fun getHistory(): List<AgentMessage>
    fun addMessage(message: AgentMessage)
    fun addUserMessage(content: String): AgentMessage
    fun addAssistantMessage(content: String): AgentMessage
    fun addSystemMessage(content: String): AgentMessage
    fun addMessages(messages: List<AgentMessage>)
    fun removeLastMessage(): AgentMessage?
    fun removeLastMessages(count: Int): List<AgentMessage>
    fun clear()
    fun replaceHistory(messages: List<AgentMessage>)
    fun copy(): AgentContext
}
```

> **Изменение:** Контекст теперь не содержит логику обрезки — это просто потокобезопасное хранилище. Стратегия обрезки управляется агентом.

---

### 3. ContextTruncationStrategy (Стратегия обрезки)

**Файл:** `agent/context/strategy/ContextTruncationStrategy.kt`

```kotlin
interface ContextTruncationStrategy {
    suspend fun truncate(
        messages: List<AgentMessage>,
        maxTokens: Int?,
        maxMessages: Int?
    ): List<AgentMessage>
}
```

Стратегия вызывается агентом после добавления каждого сообщения.

---

### 4. Компрессия истории (Summary)

#### Принцип работы

```
История: [M1, M2, M3, M4, M5, M6, M7, M8, M9, M10, M11, M12, M13, M14, M15]
                                                        ↑
                                                keepRecentCount = 5
                                                summaryBlockSize = 10

Результат в API запросе:
  [System Prompt]
  [Summary: "краткое описание M1..M10"]  ← Сжатые сообщения
  [M11, M12, M13, M14, M15]              ← Последние N сообщений
  [Новое сообщение пользователя]
```

#### Компоненты

**SummaryTruncationStrategy** (`agent/context/strategy/SummaryTruncationStrategy.kt`)

```kotlin
class SummaryTruncationStrategy(
    private val summaryProvider: SummaryProvider,
    private val summaryStorage: SummaryStorage,
    private val keepRecentCount: Int = 10,
    private val summaryBlockSize: Int = 10
) : ContextTruncationStrategy {
    
    override suspend fun truncate(...): List<AgentMessage>
    
    // Suspend версии (предпочтительно)
    suspend fun getSummariesAsMessagesSuspend(): List<AgentMessage>
    suspend fun clearSummariesSuspend()
    suspend fun getCompressedMessageCountSuspend(): Int
    
    // Синхронные версии (для совместимости, используют runBlocking)
    fun getSummariesAsMessages(): List<AgentMessage>
    fun clearSummaries()
    fun getCompressedMessageCount(): Int
}
```

**SummaryStorage** (`agent/context/summary/SummaryStorage.kt`)

```kotlin
interface SummaryStorage {
    suspend fun getSummaries(): List<ConversationSummary>
    suspend fun addSummary(summary: ConversationSummary)
    suspend fun clear()
    suspend fun getSize(): Int
    suspend fun isEmpty(): Boolean
    suspend fun loadSummaries(summaries: List<ConversationSummary>)
}

// Реализации:
class InMemorySummaryStorage : SummaryStorage      // В памяти (Mutex)
class JsonSummaryStorage(context) : SummaryStorage // В JSON-файле (Mutex + Dispatchers.IO)
```

> **Важно:** Все методы `SummaryStorage` теперь `suspend` для корректной работы с IO и синхронизацией через `Mutex` вместо `synchronized`.

**SummaryProvider** (`agent/context/summary/SummaryProvider.kt`)

```kotlin
interface SummaryProvider {
    suspend fun summarize(messages: List<AgentMessage>): String
}

class LLMSummaryProvider(api: StatsLLMApi, model: String) : SummaryProvider
class SimpleSummaryProvider : SummaryProvider  // Fallback без LLM
```

---

### 5. Data Layer (Слой данных)

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

Декоратор, добавляющий статистику:

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
    suspend fun getAllSessions(): List<ChatSession>
    suspend fun clearAll()
}
```

---

### 6. Domain Models (Доменные модели)

**Файл:** `domain/Models.kt`

```kotlin
data class Message(...)
data class TokenStats(...)
data class SessionTokenStats(...)
data class ChatRequest(...)
sealed class StreamResult { ... }
sealed class StatsStreamResult { ... }
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
    val defaultMaxTokens: Long?,       // Макс. токенов в ответе
    val defaultSystemPrompt: String?,
    val defaultStopSequences: List<String>?,
    val keepConversationHistory: Boolean,
    val maxHistorySize: Int?,          // Макс. сообщений в истории
    val maxTokens: Int?                // Макс. токенов в контексте
)

sealed class AgentStreamEvent {
    data class ContentDelta(val text: String) : AgentStreamEvent()
    data class Completed(val tokenStats: TokenStats, val durationMs: Long) : AgentStreamEvent()
    data class Error(val exception: Throwable) : AgentStreamEvent()
}
```

---

### 7. UI Layer (Слой UI)

#### ViewModel
**Файл:** `ui/AgentChatViewModel.kt`

```kotlin
class AgentChatViewModel(
    private val agent: Agent,
    private val availableModels: List<String>,
    private val chatHistoryRepository: ChatHistoryRepository?,
    private val summaryStorage: SummaryStorage?
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

---

### 8. Dependency Injection

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
    val chatHistoryRepository: ChatHistoryRepository by lazy { JsonChatHistoryRepository(context) }
    val summaryStorage: JsonSummaryStorage by lazy { JsonSummaryStorage(context) }
    
    fun createAgentChatViewModel(): AgentChatViewModel
    
    fun createAgentChatViewModelWithCompression(
        keepRecentCount: Int = 10,
        summaryBlockSize: Int = 10,
        useLLMForSummary: Boolean = true,
        summaryModel: String? = null
    ): AgentChatViewModel
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
│  ┌───────────────────────────────────────────────────────────────────┐ │
│  │                        SimpleLLMAgent                              │ │
│  │  ┌─────────────────┐    ┌────────────────────────────────────┐   │ │
│  │  │  AgentContext   │    │      TruncationStrategy            │   │ │
│  │  │ (хранилище msg) │    │ (SummaryTruncationStrategy и др.)  │   │ │
│  │  └─────────────────┘    └──────────────┬─────────────────────┘   │ │
│  │                                        │                          │ │
│  │                         ┌──────────────┴──────────────┐          │ │
│  │                         │     SummaryProvider         │          │ │
│  │                         │  (LLMSummaryProvider)       │          │ │
│  │                         └─────────────────────────────┘          │ │
│  └───────────────────────────────────────────────────────────────────┘ │
│                                    │                                    │
└────────────────────────────────────┼────────────────────────────────────┘
                                     │
                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                            Data Layer                                    │
│  ┌────────────────────┐    ┌──────────────┐    ┌─────────────────────┐ │
│  │ StatsTrackingLLMApi│───▶│   OpenAIApi  │───▶│  HTTP (OkHttp SSE)  │ │
│  └────────────────────┘    └──────────────┘    └─────────────────────┘ │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                    Persistence                                   │   │
│  │  ┌─────────────────────────┐    ┌────────────────────────────┐  │   │
│  │  │ ChatHistoryRepository   │    │ JsonChatHistoryRepository  │  │   │
│  │  └─────────────────────────┘    └────────────────────────────┘  │   │
│  │                                                                  │   │
│  │  ┌─────────────────────────┐    ┌────────────────────────────┐  │   │
│  │  │    SummaryStorage       │    │   JsonSummaryStorage       │  │   │
│  │  │  (suspend interface)    │    │  (Mutex + Dispatchers.IO)  │  │   │
│  │  └─────────────────────────┘    └────────────────────────────┘  │   │
│  └─────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 🏛️ Архитектурные решения

### Разделение ответственности

| Компонент | Ответственность |
|-----------|-----------------|
| `AgentContext` | Простое хранилище сообщений (потокобезопасное) |
| `Agent` | Бизнес-логика: отправка запросов, применение стратегии обрезки |
| `TruncationStrategy` | Логика обрезки/компрессии истории |
| `SummaryStorage` | Хранение summaries (IO операции) |

### Потокобезопасность

- **`AgentContext`** — использует `synchronized` для синхронных операций
- **`SummaryStorage`** — использует `Mutex` для suspend-операций
- **`SimpleLLMAgent.chatStream()`** — использует `channelFlow` для избежания deadlock

### Почему `channelFlow` вместо `flow`?

```kotlin
// ❌ Может вызвать deadlock
flow {
    api.sendMessageStream(request).collect { result ->
        emit(transform(result))  // emit внутри collect
    }
}

// ✅ Безопасно
channelFlow {
    api.sendMessageStream(request).collect { result ->
        send(transform(result))  // send внутри collect
    }
}
```

### Почему `Mutex` вместо `synchronized` в suspend-функциях?

```kotlin
// ❌ Блокирует поток, может вызвать проблемы
suspend fun badExample() {
    synchronized(lock) {
        withContext(Dispatchers.IO) { ... }  // Поток заблокирован!
    }
}

// ✅ Приостанавливает корутину, не блокирует поток
suspend fun goodExample() {
    mutex.withLock {
        withContext(Dispatchers.IO) { ... }  // Корутина приостановлена
    }
}
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
    
    // Вариант 1: Без компрессии
    private val viewModel by lazy {
        appModule.createAgentChatViewModel()
    }
    
    // Вариант 2: С компрессией истории
    private val viewModelWithCompression by lazy {
        appModule.createAgentChatViewModelWithCompression(
            keepRecentCount = 10,
            summaryBlockSize = 10,
            useLLMForSummary = true
        )
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
    truncationStrategy(myStrategy)  // Опционально
}
```

### 3. Создание агента с компрессией

```kotlin
val summaryStorage = JsonSummaryStorage(context)

val summaryProvider = LLMSummaryProvider(
    api = statsLLMApi,
    model = "gpt-4"
)

val truncationStrategy = SummaryTruncationStrategy(
    summaryProvider = summaryProvider,
    summaryStorage = summaryStorage,
    keepRecentCount = 10,
    summaryBlockSize = 10
)

val agent = SimpleLLMAgent(
    api = statsLLMApi,
    initialConfig = agentConfig,
    agentContext = SimpleAgentContext(),
    truncationStrategy = truncationStrategy
)
```

### 4. Отправка сообщения

```kotlin
agent.send("Привет!")
    .collect { event ->
        when (event) {
            is AgentStreamEvent.ContentDelta -> print(event.text)
            is AgentStreamEvent.Completed -> println("\nTokens: ${event.tokenStats}")
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

### Файлы данных

| Файл | Путь | Содержимое |
|------|------|------------|
| История чата | `files/chat_history.json` | Сессии, сообщения, статистика |
| Summaries | `files/summaries.json` | Сжатые блоки сообщений |

---

## 🔧 Стратегии обрезки контекста

| Стратегия | Описание | Когда использовать |
|-----------|----------|-------------------|
| `SimpleContextTruncationStrategy` | Удаляет старейшие сообщения | По умолчанию |
| `PreserveSystemTruncationStrategy` | Сохраняет системные сообщения | Важен system prompt |
| `SummaryTruncationStrategy` | Сжимает старые сообщения в summary | Длинные диалоги, экономия токенов |

---

## 💡 Экономия токенов с компрессией

**Пример:**
- 50 сообщений по ~100 токенов = 5000 токенов
- С компрессией (keepRecent=10, summary ~200 токенов): 1000 + 200 = 1200 токенов
- **Экономия: ~75%**

---

## 🧪 Тестирование

```kotlin
@Test
fun `agent should collect history`() = runTest {
    val mockApi = MockStatsLLMApi()
    val agent = SimpleLLMAgent(mockApi, AgentConfig(defaultModel = "test"))
    
    agent.send("Hello").collect()
    
    assertEquals(2, agent.conversationHistory.size)
}

@Test
fun `should compress old messages to summary`() = runTest {
    val storage = InMemorySummaryStorage()
    val strategy = SummaryTruncationStrategy(
        summaryProvider = MockSummaryProvider("Summary"),
        summaryStorage = storage,
        keepRecentCount = 5,
        summaryBlockSize = 10
    )
    
    val agent = SimpleLLMAgent(
        api = mockApi,
        initialConfig = config,
        truncationStrategy = strategy
    )
    
    // Добавляем 15 сообщений
    repeat(15) { agent.addToHistory(userMessage("Message $it")) }
    
    // Проверяем
    assertEquals(1, storage.getSize())
    assertEquals(5, agent.conversationHistory.size)
}
```

---

## ⚠️ Важные особенности

1. **Агент не зависит от Android** — можно тестировать без эмулятора

2. **Стратегия обрезки в Agent, не в Context** — чёткое разделение: контекст хранит, агент управляет

3. **Все методы SummaryStorage — suspend** — корректная работа с IO через Mutex

4. **channelFlow для стриминга** — избежание deadlock при collect + emit

5. **Конфигурация через BuildConfig** — API ключи в `local.properties`

---

## 🚫 Чего избегать

- Не добавлять Android-зависимости в `agent/` слой
- Не использовать `synchronized` в suspend-функциях — только `Mutex`
- Не использовать `flow { collect { emit } }` — только `channelFlow` или `emitAll`
- Не блокировать main thread — все IO на `Dispatchers.IO`
- Не использовать `GlobalScope` — только `viewModelScope` или структурированные scope
