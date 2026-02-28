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
│           ├── SummaryModels.kt    # ConversationSummary (с оригинальными сообщениями)
│           ├── SummaryStorage.kt   # Интерфейс (suspend) + InMemorySummaryStorage
│           ├── JsonSummaryStorage.kt # JSON-реализация с persistence
│           ├── SummaryProvider.kt  # Интерфейс генерации summary
│           └── LLMSummaryProvider.kt # Реализация через LLM
├── data/                           # 📡 Слой данных (API, persistence)
│   ├── Api.kt                      # LLMApi интерфейс + OpenAIApi реализация
│   ├── StatsTrackingLLMApi.kt      # Декоратор для сбора статистики
│   └── persistence/                # Сохранение истории чата
│       ├── ChatHistoryModels.kt    # Модели для сериализации (+ summaries с originalMessages)
│       ├── ChatHistoryMapper.kt    # Конвертеры между моделями
│       ├── ChatHistoryRepository.kt # Интерфейс репозитория
│       └── JsonChatHistoryRepository.kt # JSON-реализация
├── domain/                         # 📦 Доменные модели
│   └── Models.kt                   # Message (+ isCompressed), TokenStats, ChatRequest и др.
├── di/                             # 💉 Dependency Injection
│   └── AppModule.kt                # Ручной DI модуль + AppContainer
└── ui/                             # 🎨 UI слой (Jetpack Compose)
    ├── AgentChatViewModel.kt       # ViewModel с MVI-подходом
    ├── ChatScreen.kt               # Главный экран чата (+ CompressedMessageBubble)
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
    val config: AgentConfig
    val context: AgentContext                           // Простое хранилище сообщений
    val truncationStrategy: ContextTruncationStrategy? // Стратегия обрезки
    val conversationHistory: List<AgentMessage>

    suspend fun chat(request: AgentRequest): AgentResponse
    fun chatStream(request: AgentRequest): Flow<AgentStreamEvent>
    fun send(message: String): Flow<AgentStreamEvent>
    fun clearHistory()
    suspend fun addToHistory(message: AgentMessage)
    fun updateConfig(newConfig: AgentConfig)
    fun updateTruncationStrategy(strategy: ContextTruncationStrategy?)
}
```

**Реализация:** `SimpleLLMAgent`
- Стриминг через `channelFlow` (избежание deadlock)
- После каждого `addMessage` применяет `truncationStrategy`
- При формировании запроса подставляет summaries из стратегии

---

### 2. AgentContext (Контекст диалога)

**Файл:** `agent/context/AgentContext.kt`

Простое потокобезопасное хранилище сообщений. Вся логика обрезки — в агенте.

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
    fun replaceHistory(messages: List<AgentMessage>)  // Используется после обрезки
    fun copy(): AgentContext
}
```

---

### 3. Компрессия истории (Summary)

#### Принцип работы

```
История: [M1, M2, ..., M15]
         ↑                ↑
         oldMessages      recentMessages (keepRecentCount = 5)
         (10 сообщений)

После компрессии:

  В LLM-запросе:                         В UI (для пользователя):
  ┌──────────────────────────────┐        ┌──────────────────────────┐
  │ [System Prompt]              │        │ M1 🗜️сжато               │
  │ [Summary: "краткое M1..M10"] │        │ M2 🗜️сжато               │
  │ [M11, M12, M13, M14, M15]   │        │ ...                      │
  │ [Новое сообщение]            │        │ M10 🗜️сжато              │
  └──────────────────────────────┘        │ M11, M12, M13, M14, M15  │
                                          │ [Новое сообщение]        │
                                          └──────────────────────────┘
```

#### ConversationSummary

**Файл:** `agent/context/summary/SummaryModels.kt`

```kotlin
data class ConversationSummary(
    val content: String,                    // Текст summary → отправляется в LLM
    val originalMessages: List<AgentMessage>, // Исходные сообщения → только для UI
    val createdAt: Long = System.currentTimeMillis()
) {
    val originalMessageCount: Int get() = originalMessages.size
}
```

> **Ключевое решение:** `originalMessages` хранятся только для отображения пользователю с пометкой "сжато". В запрос к LLM они **не включаются** — вместо них подставляется `content` (текст summary).

#### SummaryTruncationStrategy

**Файл:** `agent/context/strategy/SummaryTruncationStrategy.kt`

```kotlin
class SummaryTruncationStrategy(
    private val summaryProvider: SummaryProvider,
    private val summaryStorage: SummaryStorage,
    private val keepRecentCount: Int = 10,
    private val summaryBlockSize: Int = 10
) : ContextTruncationStrategy {

    override suspend fun truncate(
        messages: List<AgentMessage>,
        maxTokens: Int?,
        maxMessages: Int?
    ): List<AgentMessage>

    // Suspend-версии (предпочтительно)
    suspend fun getSummariesAsMessagesSuspend(): List<AgentMessage>
    suspend fun clearSummariesSuspend()
    suspend fun getCompressedMessageCountSuspend(): Int

    // Синхронные версии (используют runBlocking, для совместимости)
    fun getSummariesAsMessages(): List<AgentMessage>
    fun clearSummaries()
    fun getCompressedMessageCount(): Int
}
```

#### SummaryStorage

**Файл:** `agent/context/summary/SummaryStorage.kt`

```kotlin
// Все методы suspend — корректная работа с IO через Mutex
interface SummaryStorage {
    suspend fun getSummaries(): List<ConversationSummary>
    suspend fun addSummary(summary: ConversationSummary)
    suspend fun clear()
    suspend fun getSize(): Int
    suspend fun isEmpty(): Boolean
    suspend fun loadSummaries(summaries: List<ConversationSummary>)
}

class InMemorySummaryStorage : SummaryStorage  // Mutex, только память
class JsonSummaryStorage(context) : SummaryStorage  // Mutex + Dispatchers.IO, файл summaries.json
```

#### SummaryProvider

**Файл:** `agent/context/summary/SummaryProvider.kt`

```kotlin
interface SummaryProvider {
    suspend fun summarize(messages: List<AgentMessage>): String
}

class LLMSummaryProvider(api: StatsLLMApi, model: String) : SummaryProvider
class SimpleSummaryProvider : SummaryProvider  // Fallback без LLM
```

---

### 4. Data Layer (Слой данных)

#### LLMApi / StatsLLMApi

```kotlin
interface LLMApi {
    fun sendMessageStream(chatRequest: ChatRequest): Flow<StreamResult>
}

// Декоратор со статистикой
interface StatsLLMApi {
    fun sendMessageStream(chatRequest: ChatRequest): Flow<StatsStreamResult>
}
```

#### ChatHistoryRepository

```kotlin
interface ChatHistoryRepository {
    suspend fun saveSession(session: ChatSession)
    suspend fun loadSession(sessionId: String): ChatSession?
    suspend fun loadActiveSession(): ChatSession?
    suspend fun getAllSessions(): List<ChatSession>
    suspend fun clearAll()
}
```

#### Модели persistence

```kotlin
data class PersistedSummary(
    val content: String,
    val originalMessages: List<PersistedAgentMessage>,  // Сохраняем для восстановления UI
    val createdAt: Long
)

data class ChatSession(
    val id: String,
    val messages: List<PersistedAgentMessage>,
    val createdAt: Long,
    val updatedAt: Long,
    val model: String? = null,
    val sessionStats: PersistedSessionStats? = null,
    val summaries: List<PersistedSummary> = emptyList()
)
```

---

### 5. Domain Models (Доменные модели)

**Файл:** `domain/Models.kt`

```kotlin
data class Message(
    val id: String,
    val isUser: Boolean,
    val text: String,
    val isLoading: Boolean = false,
    val tokenStats: TokenStats? = null,
    val responseDurationMs: Long? = null,
    val isCompressed: Boolean = false  // true → сообщение из сжатого блока, только UI
)
```

---

### 6. UI Layer (Слой UI)

#### ViewModel — формирование списка сообщений

**Файл:** `ui/AgentChatViewModel.kt`

```kotlin
class AgentChatViewModel(
    private val agent: Agent,
    private val availableModels: List<String>,
    private val chatHistoryRepository: ChatHistoryRepository?,
    private val summaryStorage: SummaryStorage?
) : ViewModel()
```

Список сообщений строится из трёх частей:

```
allMessages =
    summaries.flatMap { it.originalMessages }   // isCompressed = true, только UI
    + agent.conversationHistory                 // активная история
    + streamingMessage?                         // текущий стрим
```

#### ChatScreen — отображение сжатых сообщений

**Файл:** `ui/ChatScreen.kt`

```kotlin
// Обычный пузырёк
@Composable
fun MessageBubble(isUser, text, isLoading, tokenStats, responseDurationMs)

// Пузырёк для сжатого сообщения
@Composable
fun CompressedMessageBubble(isUser, text)
// - alpha = 0.5f (приглушённый)
// - курсив, меньший шрифт
// - метка "🗜️ сжато" над текстом
// - прямоугольные углы (RoundedCornerShape 8.dp)
```

Выбор пузырька в `MessageList`:

```kotlin
if (message.isCompressed) {
    CompressedMessageBubble(isUser = message.isUser, text = message.text)
} else {
    MessageBubble(...)
}
```

#### UI State

```kotlin
data class ChatUiState(
    val messages: List<Message>,       // Сжатые + активные + стрим
    val availableModels: List<String>,
    val settingsData: SettingsData,
    val currentInput: String,
    val isLoading: Boolean,
    val isSettingsOpen: Boolean,
    val error: String?,
    val sessionStats: SessionTokenStats?,
    val compressedMessageCount: Int    // Количество сжатых сообщений (для footer)
)
```

---

### 7. Dependency Injection

**Файл:** `di/AppModule.kt`

```kotlin
class AppModule(context, apiKey, baseUrl, availableModels) {
    val llmApi: LLMApi
    val statsLLMApi: StatsLLMApi
    val agentConfig: AgentConfig
    val agent: Agent                                   // Без компрессии
    val chatHistoryRepository: ChatHistoryRepository   // JsonChatHistoryRepository
    val summaryStorage: JsonSummaryStorage             // Файл summaries.json

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
│  ┌──────────────┐    ┌──────────────────────┐    ┌──────────────────┐  │
│  │ MainActivity │───▶│ AgentChatViewModel   │◀──▶│   ChatScreen     │  │
│  └──────────────┘    │  (MVI Intents)       │    │                  │  │
│                      │                      │    │ MessageBubble    │  │
│                      │ allMessages =        │    │ Compressed       │  │
│                      │  compressed (UI only)│    │ MessageBubble    │  │
│                      │  + history           │    └──────────────────┘  │
│                      │  + streaming         │                           │
│                      └──────────┬───────────┘                           │
└─────────────────────────────────┼───────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                            Agent Layer                                   │
│  ┌───────────────────────────────────────────────────────────────────┐ │
│  │                        SimpleLLMAgent                              │ │
│  │                                                                   │ │
│  │  addMessageWithTruncation()                                       │ │
│  │    → context.addMessage()                                         │ │
│  │    → truncationStrategy.truncate()                                │ │
│  │    → context.replaceHistory()                                     │ │
│  │                                                                   │ │
│  │  buildMessageList() для LLM-запроса:                              │ │
│  │    [SystemPrompt] + [SummaryMessages] + [ActiveHistory] + [New]   │ │
│  │                                ↑                                  │ │
│  │                   НЕ включает originalMessages!                   │ │
│  │                                                                   │ │
│  │  ┌─────────────────┐    ┌──────────────────────────────────────┐ │ │
│  │  │  AgentContext   │    │     SummaryTruncationStrategy        │ │ │
│  │  │ (хранилище msg) │    │  keepRecentCount / summaryBlockSize  │ │ │
│  │  └─────────────────┘    └──────────────┬───────────────────────┘ │ │
│  │                                        │                          │ │
│  │                         ┌──────────────┴──────────┐              │ │
│  │                         │    SummaryProvider       │              │ │
│  │                         │  (LLMSummaryProvider)    │              │ │
│  │                         └─────────────────────────┘              │ │
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
│  Persistence:                                                           │
│  ┌──────────────────────────────┐  ┌──────────────────────────────┐    │
│  │ JsonChatHistoryRepository    │  │ JsonSummaryStorage            │    │
│  │ chat_history.json            │  │ summaries.json               │    │
│  │ (messages + summaries)       │  │ (Mutex + Dispatchers.IO)     │    │
│  └──────────────────────────────┘  └──────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 🏛️ Архитектурные решения

### Разделение ответственности

| Компонент | Ответственность |
|-----------|-----------------|
| `AgentContext` | Потокобезопасное хранилище сообщений (`synchronized`) |
| `Agent` | Отправка запросов, применение стратегии обрезки |
| `TruncationStrategy` | Логика обрезки / компрессии истории (`suspend`) |
| `SummaryStorage` | Хранение summaries (IO через `Mutex` + `Dispatchers.IO`) |
| `ConversationSummary.content` | Текст summary → отправляется в LLM |
| `ConversationSummary.originalMessages` | Исходные сообщения → только для UI |
| `ViewModel` | Сборка `allMessages` из сжатых + активных + стримящихся |
| `CompressedMessageBubble` | Отображение сжатых сообщений с пометкой |

### Что уходит в LLM, что нет

```
LLM-запрос:
  [system] "You are a helpful assistant"
  [system] "Previous conversation summary: ..."   ← content из ConversationSummary
  [user]   "M11"
  [assistant] "A11"
  ...
  [user]   "M15"                                  ← последние keepRecentCount сообщений
  [user]   "Новый вопрос"

НЕ уходит в LLM:
  originalMessages (M1..M10)                      ← только отображаются в UI
```

### Потокобезопасность

| Компонент | Механизм | Причина |
|-----------|----------|---------|
| `SimpleAgentContext` | `synchronized` | Синхронные методы |
| `SummaryStorage` | `Mutex` | suspend-методы с IO |
| `SimpleLLMAgent.chatStream` | `channelFlow` | Избежание deadlock |

### Почему `channelFlow` вместо `flow`

```kotlin
// ❌ Deadlock: collect блокирует, emit ждёт
flow {
    upstream.collect { emit(transform(it)) }
}

// ✅ Безопасно: channel не блокирует корутину
channelFlow {
    upstream.collect { send(transform(it)) }
}
```

### Почему `Mutex` вместо `synchronized` в suspend-функциях

```kotlin
// ❌ Блокирует поток пока корутина приостановлена внутри
suspend fun bad() {
    synchronized(lock) { withContext(Dispatchers.IO) { ... } }
}

// ✅ Приостанавливает корутину, поток свободен
suspend fun good() {
    mutex.withLock { withContext(Dispatchers.IO) { ... } }
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

    // Без компрессии
    private val viewModel by lazy {
        appModule.createAgentChatViewModel()
    }

    // С компрессией и отображением сжатых сообщений в UI
    private val viewModelWithCompression by lazy {
        appModule.createAgentChatViewModelWithCompression(
            keepRecentCount = 10,
            summaryBlockSize = 10,
            useLLMForSummary = true
        )
    }
}
```

### 2. Создание агента с компрессией вручную

```kotlin
val summaryStorage = JsonSummaryStorage(context)

val truncationStrategy = SummaryTruncationStrategy(
    summaryProvider = LLMSummaryProvider(statsLLMApi, model = "gpt-4"),
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

### 3. Отправка сообщения

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
| История чата | `files/chat_history.json` | Сессии, сообщения, summaries (с originalMessages) |
| Summaries | `files/summaries.json` | Сжатые блоки с оригинальными сообщениями |

### Формат summaries.json

```json
{
  "version": 1,
  "summaries": [
    {
      "content": "User asked about Kotlin, assistant explained coroutines.",
      "originalMessages": [
        {"role": "USER", "content": "Расскажи про корутины", "timestamp": 1234567890},
        {"role": "ASSISTANT", "content": "Корутины — это...", "timestamp": 1234567891}
      ],
      "createdAt": 1234567900
    }
  ]
}
```

---

## 🔧 Стратегии обрезки контекста

| Стратегия | Описание | Когда использовать |
|-----------|----------|-------------------|
| `SimpleContextTruncationStrategy` | Удаляет старейшие сообщения | По умолчанию |
| `PreserveSystemTruncationStrategy` | Сохраняет системные сообщения | Важен system prompt |
| `SummaryTruncationStrategy` | Сжимает старые → summary, хранит оригиналы для UI | Длинные диалоги |

---

## 🧪 Тестирование

```kotlin
@Test
fun `compressed messages visible in UI but not sent to LLM`() = runTest {
    val storage = InMemorySummaryStorage()
    val strategy = SummaryTruncationStrategy(
        summaryProvider = MockSummaryProvider("Summary text"),
        summaryStorage = storage,
        keepRecentCount = 5,
        summaryBlockSize = 10
    )
    val agent = SimpleLLMAgent(mockApi, config, truncationStrategy = strategy)

    // Добавляем 15 сообщений — 10 должны быть сжаты
    repeat(15) { agent.addToHistory(AgentMessage(Role.USER, "Message $it")) }

    // В истории агента — только последние 5
    assertEquals(5, agent.conversationHistory.size)

    // В storage — 1 summary с 10 оригинальными сообщениями
    val summaries = storage.getSummaries()
    assertEquals(1, summaries.size)
    assertEquals(10, summaries.first().originalMessages.size)

    // content отправится в LLM, originalMessages — нет
    assertNotEmpty(summaries.first().content)
}
```

---

## ⚠️ Важные особенности

1. **Агент не зависит от Android** — можно тестировать без эмулятора

2. **Два уровня истории в UI:**
   - Сжатые сообщения (`isCompressed = true`) — из `summary.originalMessages`, не идут в LLM
   - Активные сообщения — из `agent.conversationHistory`, идут в LLM

3. **Стратегия обрезки в Agent, не в Context** — контекст только хранит, агент управляет

4. **Все методы SummaryStorage — suspend** — `Mutex` вместо `synchronized`

5. **`channelFlow` для стриминга** — избежание deadlock при `collect` + `send`

---

## 🚫 Чего избегать

- Не добавлять Android-зависимости в `agent/` слой
- Не использовать `synchronized` в suspend-функциях — только `Mutex`
- Не использовать `flow { collect { emit } }` — только `channelFlow`
- Не блокировать main thread — все IO на `Dispatchers.IO`
- Не включать `originalMessages` в LLM-запрос — только `content` из summary
- Не использовать `GlobalScope` — только `viewModelScope` или структурированные scope
