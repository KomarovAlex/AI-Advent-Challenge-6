package ru.koalexse.aichallenge.agent.context.strategy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.koalexse.aichallenge.agent.AgentMessage
import ru.koalexse.aichallenge.agent.Role
import ru.koalexse.aichallenge.agent.StatsLLMApi
import ru.koalexse.aichallenge.agent.context.facts.Fact
import ru.koalexse.aichallenge.agent.context.facts.FactsStorage
import ru.koalexse.aichallenge.domain.ApiMessage
import ru.koalexse.aichallenge.domain.ChatRequest
import ru.koalexse.aichallenge.domain.StatsStreamResult

/**
 * Стратегия «Sticky Facts / Key-Value Memory».
 *
 * Принцип работы:
 * 1. Последние [keepRecentCount] сообщений остаются в LLM-контексте «как есть».
 * 2. Сообщения, вышедшие за пределы [keepRecentCount], **вытесняются из LLM-контекста**
 *    и сохраняются в [factsStorage] как compressed-сообщения — только для UI.
 * 3. В LLM-запрос уходят: факты (как system-сообщение через [getAdditionalSystemMessages])
 *    + последние N сообщений. Вытесненные сообщения в LLM не включаются.
 * 4. Факты обновляются по явному запросу пользователя через [refreshFacts].
 *
 * ### Отображение в UI
 * ```
 * 📌 Key facts bubble        ← facts (всегда сверху)
 * [M1🗜️ … M10🗜️]            ← compressed messages (только UI, не идут в LLM)
 * [M11 … M20]               ← recent messages (идут в LLM)
 * ```
 *
 * ### LLM-запрос
 * ```
 * [system: "Key facts: goal: X, language: Kotlin"]   ← из getAdditionalSystemMessages()
 * [M11 … M20]                                         ← из _context (recent only)
 * ```
 *
 * ### Capability-интерфейсы
 * Для управления фактами из ViewModel используйте приведение типа:
 * ```kotlin
 * (agent.truncationStrategy as? StickyFactsStrategy)?.getFacts()
 * (agent.truncationStrategy as? StickyFactsStrategy)?.getCompressedMessages()
 * ```
 *
 * @param api             API для LLM-вызова при обновлении фактов
 * @param factsStorage    хранилище фактов и compressed-сообщений (persisted)
 * @param keepRecentCount количество последних сообщений, остающихся в LLM-контексте
 * @param factsModel      модель, используемая для обновления фактов
 * @param tokenEstimator  функция оценки токенов
 */
class StickyFactsStrategy(
    private val api: StatsLLMApi,
    private val factsStorage: FactsStorage,
    val keepRecentCount: Int = DEFAULT_KEEP_RECENT,
    private val factsModel: String,
    private val tokenEstimator: TokenEstimator = TokenEstimators.default
) : ContextTruncationStrategy {

    init {
        require(keepRecentCount > 0) { "keepRecentCount must be positive" }
    }

    // ==================== ContextTruncationStrategy ====================

    /**
     * Обрезает историю для LLM-запроса.
     *
     * Сообщения за пределами [keepRecentCount] **не удаляются навсегда** — они
     * накапливаются в [factsStorage] как compressed-сообщения и доступны UI
     * через [getCompressedMessages]. В возвращаемый список (который идёт в `_context`
     * агента и затем в LLM-запрос) попадают только последние [keepRecentCount] сообщений.
     */
    override suspend fun truncate(
        messages: List<AgentMessage>,
        maxTokens: Int?,
        maxMessages: Int?
    ): List<AgentMessage> {
        if (messages.isEmpty()) return messages

        val recentMessages = messages.takeLast(keepRecentCount)
        val oldMessages = messages.dropLast(keepRecentCount)

        if (oldMessages.isNotEmpty()) {
            // Добавляем вытесненные сообщения к уже накопленным compressed
            val alreadyCompressed = factsStorage.getCompressedMessages()
            factsStorage.setCompressedMessages(alreadyCompressed + oldMessages)

            // В _context и LLM уходят только recent сообщения
            var result = recentMessages
            if (maxMessages != null && result.size > maxMessages) {
                result = result.takeLast(maxMessages)
            }
            if (maxTokens != null) {
                result = TruncationUtils.truncateByTokens(result, maxTokens, tokenEstimator)
            }
            return result
        }

        // Все сообщения умещаются в keepRecentCount — стандартная обрезка по лимитам
        var result = messages
        if (maxMessages != null && result.size > maxMessages) {
            result = result.takeLast(maxMessages)
        }
        if (maxTokens != null) {
            result = TruncationUtils.truncateByTokens(result, maxTokens, tokenEstimator)
        }
        return result
    }

    /**
     * Возвращает накопленные факты как system-сообщение для LLM-запроса.
     * Переопределяет метод интерфейса — агент вызывает его без приведения типов.
     */
    override suspend fun getAdditionalSystemMessages(): List<AgentMessage> {
        val facts = factsStorage.getFacts()
        if (facts.isEmpty()) return emptyList()

        val content = buildString {
            appendLine("Key facts about this conversation (use as context):")
            facts.forEach { fact ->
                appendLine("- ${fact.key}: ${fact.value}")
            }
        }.trimEnd()

        return listOf(AgentMessage(role = Role.SYSTEM, content = content))
    }

    /** Очищает все факты и compressed-сообщения — реализация [ContextTruncationStrategy.clear]. */
    override suspend fun clear() = clearFacts()

    // ==================== Facts management ====================

    /** Возвращает текущие факты — для UI и persistence. */
    suspend fun getFacts(): List<Fact> = factsStorage.getFacts()

    /** Очищает все факты и compressed-сообщения. */
    suspend fun clearFacts() = factsStorage.clear()

    // ==================== Compressed messages (capability) ====================

    /**
     * Возвращает сообщения, вытесненные из LLM-контекста при обрезке.
     * Используются только для отображения в UI с пометкой «сжатые».
     */
    suspend fun getCompressedMessages(): List<AgentMessage> =
        factsStorage.getCompressedMessages()

    /**
     * Загружает compressed-сообщения при восстановлении сессии.
     */
    suspend fun loadCompressedMessages(messages: List<AgentMessage>) =
        factsStorage.setCompressedMessages(messages)

    // ==================== Facts refresh ====================

    /**
     * Запускает LLM-вызов для обновления фактов на основе переданной истории.
     *
     * Принимает только **recent** сообщения (те, что сейчас находятся в `_context` агента).
     * Вытесненные сообщения в LLM на этом этапе не передаются — они уже были учтены
     * при предыдущих вызовах [refreshFacts] и отражены в существующих фактах.
     *
     * Возвращает обновлённый список фактов (уже сохранённый в [factsStorage]).
     *
     * @param history текущая активная (recent) история диалога из `agent.conversationHistory`
     */
    suspend fun refreshFacts(history: List<AgentMessage>): List<Fact> =
        withContext(Dispatchers.IO) {
            if (history.isEmpty()) return@withContext factsStorage.getFacts()

            val conversationText = history.joinToString("\n") { msg ->
                val role = when (msg.role) {
                    Role.USER      -> "User"
                    Role.ASSISTANT -> "Assistant"
                    Role.SYSTEM    -> "System"
                }
                "$role: ${msg.content}"
            }

            val existingFacts = factsStorage.getFacts()
            val existingFactsText = if (existingFacts.isEmpty()) {
                "No facts extracted yet."
            } else {
                existingFacts.joinToString("\n") { "- ${it.key}: ${it.value}" }
            }

            val request = ChatRequest(
                messages = listOf(
                    ApiMessage(role = "system", content = FACTS_EXTRACTION_PROMPT),
                    ApiMessage(
                        role = "user",
                        content = buildString {
                            appendLine("Existing facts:")
                            appendLine(existingFactsText)
                            appendLine()
                            appendLine("Recent conversation:")
                            append(conversationText)
                        }
                    )
                ),
                model = factsModel,
                temperature = 0.2f,
                max_tokens = 800L
            )

            val responseBuilder = StringBuilder()
            api.sendMessageStream(request).collect { result ->
                if (result is StatsStreamResult.Content) responseBuilder.append(result.text)
            }

            val newFacts = parseFactsResponse(responseBuilder.toString().trim())
            factsStorage.replaceFacts(newFacts)
            newFacts
        }

    // ==================== Private ====================

    /**
     * Парсит ответ LLM формата:
     * ```
     * key1: value1
     * key2: value2
     * ```
     */
    private fun parseFactsResponse(response: String): List<Fact> {
        val now = System.currentTimeMillis()
        return response.lines()
            .mapNotNull { line ->
                val cleaned = line.trimStart('-', '*', '•', ' ')
                val colonIdx = cleaned.indexOf(':')
                if (colonIdx > 0) {
                    val key = cleaned.substring(0, colonIdx).trim()
                    val value = cleaned.substring(colonIdx + 1).trim()
                    if (key.isNotEmpty() && value.isNotEmpty()) Fact(key, value, now) else null
                } else null
            }
    }

    companion object {
        const val DEFAULT_KEEP_RECENT = 10

        private const val FACTS_EXTRACTION_PROMPT =
            """You are a fact extractor. Your task is to maintain a key-value list of important facts from the conversation.

Instructions:
- Extract or update key facts: goals, constraints, preferences, decisions, agreements, important context
- Merge with existing facts — update changed values, keep unchanged ones, remove outdated ones
- Keep facts concise (one line per fact)
- Use short descriptive keys (e.g. "goal", "programming language", "deadline")
- Write in the same language as the conversation

Respond ONLY with the facts in this format (one per line):
key: value

If there are no meaningful facts, respond with exactly: NO_FACTS"""
    }
}
