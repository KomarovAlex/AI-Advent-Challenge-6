# Agent Module

Модуль агента для работы с LLM API.

## Обзор

Агент — это абстракция над LLM API, которая инкапсулирует:
- Формирование запросов к API
- Обработку ответов (включая стриминг)
- Управление историей диалога
- Сбор статистики (токены, время ответа)

### Преимущества использования агента

1. **Чистая архитектура**: Агент не зависит от Android фреймворка
2. **Тестируемость**: Легко мокать для unit-тестов
3. **Переиспользуемость**: Один агент можно использовать в разных контекстах
4. **Инкапсуляция**: Логика работы с LLM скрыта от UI

## Быстрый старт

### Создание агента

```kotlin
// Через фабрику
val agent = AgentFactory.createOpenAIAgent(
    apiKey = "sk-xxx",
    baseUrl = "https://api.openai.com/v1/chat/completions",
    config = AgentFactory.defaultConfig("gpt-4")
)

// Через builder (DSL)
val agent = buildAgent {
    withOpenAI("sk-xxx", "https://api.openai.com/v1/chat/completions")
    model("gpt-4")
    temperature(0.7f)
    systemPrompt("Ты — полезный ассистент.")
    keepHistory(true)
}
```

### Отправка сообщений

```kotlin
// Простой способ (стриминг)
agent.send("Привет!")
    .collect { event ->
        when (event) {
            is AgentStreamEvent.ContentDelta -> print(event.text)
            is AgentStreamEvent.Completed -> println("\nГотово!")
            is AgentStreamEvent.Error -> println("Ошибка: ${event.exception}")
        }
    }

// Полный контроль (без стриминга)
val response = agent.chat(
    AgentRequest(
        userMessage = "Привет!",
        model = "gpt-4",
        temperature = 0.5f
    )
)
println(response.content)
```

## Архитектура

```
┌─────────────────────────────────────────────────────────┐
│                      UI Layer                           │
│  ┌─────────────────┐     ┌──────────────────────────┐  │
│  │  ChatViewModel  │     │  AgentChatViewModel      │  │
│  └────────┬────────┘     └────────────┬─────────────┘  │
│           │                           │                 │
└───────────┼───────────────────────────┼─────────────────┘
            │                           │
            ▼                           ▼
┌───────────────────────┐    ┌────────────────────────────┐
│    ChatRepository     │    │          Agent             │
│   (старая версия)     │    │   (новая архитектура)      │
└───────────┬───────────┘    └────────────┬───────────────┘
            │                             │
            ▼                             ▼
┌─────────────────────────────────────────────────────────┐
│                     StatsLLMApi                         │
│              (добавляет статистику)                     │
└────────────────────────┬────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────┐
│                       LLMApi                            │
│                    (OpenAIApi)                          │
└─────────────────────────────────────────────────────────┘
```

## Модели данных

### AgentRequest

Запрос к агенту:

```kotlin
data class AgentRequest(
    val userMessage: String,                    // Текст сообщения
    val conversationHistory: List<AgentMessage>, // История (опционально)
    val systemPrompt: String?,                  // Системный промпт
    val model: String,                          // Модель
    val temperature: Float?,                    // Температура (0-2)
    val maxTokens: Long?,                       // Макс. токенов
    val stopSequences: List<String>?            // Стоп-последовательности
)
```

### AgentResponse

Полный ответ (для не-стримингового режима):

```kotlin
data class AgentResponse(
    val content: String,           // Текст ответа
    val tokenStats: TokenStats?,   // Статистика токенов
    val durationMs: Long?,         // Время генерации
    val model: String              // Использованная модель
)
```

### AgentStreamEvent

События стриминга:

```kotlin
sealed class AgentStreamEvent {
    data class ContentDelta(val text: String)     // Чанк текста
    data class Completed( val tokenStats: TokenStats, val durationMs: Long)       // Завершение
    data class Error(val exception: Throwable)    // Ошибка
}
```

### AgentConfig

Конфигурация агента:

```kotlin
data class AgentConfig(
    val defaultModel: String,
    val defaultTemperature: Float?,
    val defaultMaxTokens: Long?,
    val defaultSystemPrompt: String?,
    val defaultStopSequences: List<String>?,
    val keepConversationHistory: Boolean,
    val maxHistorySize: Int
)
```

## Управление историей

Агент может автоматически управлять историей диалога:

```kotlin
// Включить сохранение истории
val agent = buildAgent {
    // ...
    keepHistory(true)
    maxHistorySize(50)  // Хранить последние 50 сообщений
}

// Просмотр истории
agent.conversationHistory.forEach { msg ->
    println("[${msg.role}]: ${msg.content}")
}

// Добавить сообщение вручную
agent.addToHistory(AgentMessage(Role.SYSTEM, "Важный контекст"))

// Очистить историю
agent.clearHistory()
```

## Обработка ошибок

```kotlin
agent.send("Тест")
    .catch { e ->
        when (e) {
            is AgentException.ApiError -> {
                // Ошибка API (сеть, сервер)
                Log.e("Agent", "API error: ${e.message}, code: ${e.statusCode}")
            }
            is AgentException.TimeoutError -> {
                // Таймаут
                Log.e("Agent", "Timeout: ${e.message}")
            }
            is AgentException.ValidationError -> {
                // Неверные параметры запроса
                Log.e("Agent", "Validation: ${e.message}")
            }
            is AgentException.ConfigurationError -> {
                // Ошибка конфигурации
                Log.e("Agent", "Config: ${e.message}")
            }
        }
    }
    .collect()
```

## Интеграция с UI

```kotlin
val viewModel = AgentChatViewModel(agent, availableModels)

// UI подписывается на состояние
viewModel.state.collect { uiState ->
    // Отображение сообщений, ошибок и т.д.
}

// Отправка сообщений
viewModel.handleIntent(ChatIntent.SendMessage("Привет!"))
```

## Примеры использования

### Чат-бот с персонажем

```kotlin
val pirate = AgentFactory.createOpenAIAgent(
    apiKey = apiKey,
    baseUrl = baseUrl,
    config = AgentFactory.chatBotConfig(
        model = "gpt-4",
        systemPrompt = "Ты — пират. Говори как морской волк."
    )
)

pirate.send("Привет!").collect { /* Йо-хо-хо! */ }
```

### Переводчик

```kotlin
val translator = appModule.createTranslatorAgent(
    sourceLanguage = "русский",
    targetLanguage = "английский"
)

val response = translator.chat(
    AgentRequest(
        userMessage = "Привет, мир!",
        model = "gpt-4"
    )
)
// response.content = "Hello, world!"
```

### Однократные запросы (без истории)

```kotlin
val agent = AgentFactory.createOpenAIAgent(
    apiKey = apiKey,
    baseUrl = baseUrl,
    config = AgentFactory.singleShotConfig("gpt-4")
)

// Каждый запрос независим
agent.chat(AgentRequest("2+2=?", model = "gpt-4"))  // 4
agent.chat(AgentRequest("Что я спрашивал?", model = "gpt-4"))  // Не знаю
```

## CLI режим

Для тестирования агента можно использовать CLI:

```bash
# Установить API ключ
export OPENAI_API_KEY=sk-xxx

# Запустить (требует настройки main функции)
./gradlew :app:run
```

Команды CLI:
- `/clear` — очистить историю
- `/history` — показать историю
- `/config` — показать конфигурацию
- `/exit` — выйти

## Тестирование

```kotlin
@Test
fun `agent returns response`() = runTest {
    val mockApi = mockk<StatsLLMApi>()
    coEvery { mockApi.sendMessageStream(any()) } returns flowOf(
        StatsStreamResult.Content("Hello"),
        StatsStreamResult.Stats(TokenStats(10, 5, 15, 100), 500)
    )
    
    val agent = SimpleLLMAgent(mockApi, AgentFactory.defaultConfig("test"))
    
    val response = agent.chat(AgentRequest("Hi", model = "test"))
    
    assertEquals("Hello", response.content)
    assertEquals(15, response.tokenStats?.totalTokens)
}
```
