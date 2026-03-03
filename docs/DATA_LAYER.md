# 📡 Data Layer

## API

```kotlin
// data/Api.kt — низкоуровневый интерфейс
interface LLMApi {
    fun sendMessageStream(chatRequest: ChatRequest): Flow<StreamResult>
}

// agent/LLMApi.kt — интерфейс для агента (инверсия зависимости)
interface StatsLLMApi {
    fun sendMessageStream(chatRequest: ChatRequest): Flow<StatsStreamResult>
}
```

`OpenAIApi` — реализует `LLMApi` через OkHttp SSE.
`StatsTrackingLLMApi` — реализует `StatsLLMApi`, оборачивает `LLMApi`, добавляет `TokenStats` и `durationMs`.

---

## Domain-модели

```kotlin
data class ChatRequest(
    val messages: List<ApiMessage>,
    val model: String,
    val stop: List<String>? = null,
    val max_tokens: Long? = null,
    val temperature: Float? = null,
    val stream: Boolean = false,
    val stream_options: StreamOptions? = null
)
data class ApiMessage(val role: String, val content: String)

data class TokenStats(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val timeToFirstTokenMs: Long? = null
)

data class Message(
    val id: String,
    val isUser: Boolean,
    val text: String,
    val isLoading: Boolean = false,
    val tokenStats: TokenStats? = null,
    val responseDurationMs: Long? = null,
    val isCompressed: Boolean = false
)
```

---

## Persistence

```kotlin
interface ChatHistoryRepository {
    suspend fun saveSession(session: ChatSession)
    suspend fun loadSession(sessionId: String): ChatSession?
    suspend fun loadActiveSession(): ChatSession?
    suspend fun setActiveSession(sessionId: String)
    suspend fun clearAll()
    // ...
}
```

`JsonChatHistoryRepository` → `chat_history.json`

---

## Профили пользователя

**Пакет:** `data/persistence/profile/`

### Profile — доменная модель

```kotlin
data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val rawText: String = "",       // свободный текст — источник для извлечения фактов
    val facts: List<String> = emptyList(), // извлечённые факты → используются в system-промпте
    val isDefault: Boolean = false  // default-профиль нельзя удалить и переименовать
) {
    companion object {
        const val DEFAULT_PROFILE_ID = "default"
        fun createDefault(): Profile  // Profile(id="default", name="Default", ...)
    }
}
```

> `facts` — именно этот список попадает в system-промпт через `ProfileSystemPromptProvider`.
> `rawText` используется только как источник для LLM-извлечения фактов в `ProfileEditViewModel`.

### JsonProfileStorage — CRUD + выбор активного профиля

```kotlin
class JsonProfileStorage(context: Context, fileName: String = "profiles.json") {
    suspend fun getAll(): List<Profile>
    suspend fun getById(id: String): Profile?
    suspend fun getSelectedId(): String
    suspend fun setSelectedId(id: String)
    suspend fun save(profile: Profile)      // insert или update; для default защищает isDefault/name
    suspend fun delete(id: String): Boolean // default удалить нельзя → false
}
```

- Хранит данные в `profiles.json` (файлы приложения).
- При первом обращении **автоматически создаёт default-профиль** (`id = "default"`).
- Потокобезопасно через `Mutex`; кэширует список в памяти.
- При удалении выбранного профиля автоматически переключается на `default`.

### Структура profiles.json

```json
{
  "version": 1,
  "selectedProfileId": "default",
  "profiles": [
    {
      "id": "default",
      "name": "Default",
      "rawText": "",
      "facts": [],
      "isDefault": true
    },
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "name": "Алексей",
      "rawText": "Я разработчик из Москвы, интересуюсь AI.",
      "facts": ["Имя: Алексей", "Локация: Москва", "Интересы: AI"],
      "isDefault": false
    }
  ]
}
```

### DI (AppModule)

```kotlin
/** Единственный экземпляр — shared между ProfileListViewModel, ProfileEditViewModel
 *  и profilePromptProvider. Создаётся через lazy в AppModule. */
val profileStorage: JsonProfileStorage by lazy { JsonProfileStorage(context) }

/** Провайдер блока профиля для system-промпта агента.
 *  Один экземпляр на приложение — при каждом запросе динамически читает активный профиль.
 *  Смена профиля пользователем отражается в следующем запросе без перезапуска агента. */
val profilePromptProvider: ProfileSystemPromptProvider by lazy {
    ActiveProfileSystemPromptProvider {
        val selectedId = profileStorage.getSelectedId()
        profileStorage.getById(selectedId)
    }
}

fun createProfileListViewModel(): ProfileListViewModel =
    ProfileListViewModel(profileStorage)

fun createProfileEditViewModel(): ProfileEditViewModel {
    val factsProvider = LLMSummaryProvider(
        api = statsLLMApi,
        model = defaultModel,
        summaryPrompt = FACTS_EXTRACTION_PROMPT,
        maxSummaryTokens = 300L,
        temperature = 0.2f
    )
    return ProfileEditViewModel(storage = profileStorage, summaryProvider = factsProvider)
}
```

`profileStorage` shared между тремя потребителями:
- `ProfileListViewModel` — список + выбор активного профиля
- `ProfileEditViewModel` — редактирование + LLM-извлечение фактов
- `profilePromptProvider` — чтение фактов активного профиля при каждом запросе к агенту

---

## JsonMemoryStorage — персистенция LayeredMemory

Три **отдельных** файла, каждый со своим `Mutex`.

| Файл | Слой | Очищается при `clearSession`? | Метод обновления |
|------|------|-------------------------------|-----------------|
| `memory_working.json` | WORKING | ✅ да | `replaceWorking` — полная замена |
| `memory_long_term.json` | LONG_TERM | ❌ нет | `appendLongTerm` — **только новые ключи** |
| `memory_compressed.json` | compressed UI | ✅ да | `setCompressedMessages` |

### appendLongTerm — гарантия неизменности существующих записей

```kotlin
override suspend fun appendLongTerm(entries: List<MemoryEntry>) {
    longTermMutex.withLock {
        ensureLongTermLoaded()
        val existingKeys = cachedLongTerm!!.map { it.key }.toSet()
        val newOnly = entries.filter { it.key !in existingKeys }  // только новые ключи
        if (newOnly.isNotEmpty()) {
            cachedLongTerm!!.addAll(newOnly)
            saveLongTermLocked()
        }
    }
}
```

`replaceLongTerm` существует, но вызывается **только** из `clearAll` — не использовать напрямую.

### Структура файлов

```json
// memory_working.json / memory_long_term.json
{
  "version": 1,
  "entries": [
    { "key": "name", "value": "Алексей", "layer": "LONG_TERM", "updatedAt": 1234567890 }
  ]
}

// memory_compressed.json
{
  "version": 1,
  "messages": [
    { "role": "user", "content": "Привет!", "timestamp": 1234567890 }
  ]
}
```

### Три независимых Mutex

```kotlin
private val workingMutex    = Mutex()
private val longTermMutex   = Mutex()
private val compressedMutex = Mutex()
```

Позволяют читать LONG_TERM и писать WORKING одновременно — без лишних блокировок.

---

## Файлы данных на устройстве

| Файл | Содержимое |
|------|------------|
| `chat_history.json` | Сессии + сообщения + summaries |
| `summaries.json` | Summaries (Summary-стратегия) |
| `facts.json` | Key-value факты (StickyFacts) |
| `branches.json` | Ветки диалога (Branching) |
| `memory_working.json` | Рабочая память (LayeredMemory) |
| `memory_long_term.json` | Долговременная память (LayeredMemory, persist навсегда) |
| `memory_compressed.json` | Вытесненные сообщения для UI (LayeredMemory) |
| `profiles.json` | Список профилей + id выбранного профиля |

---

## Конфигурация (local.properties)

```properties
OPENAI_API_KEY=sk-xxx
OPENAI_URL=https://api.openai.com/v1/chat/completions
OPENAI_MODELS=gpt-4,gpt-3.5-turbo
```
