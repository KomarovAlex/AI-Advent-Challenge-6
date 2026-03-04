package ru.koalexse.aichallenge.agent.context.strategy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.koalexse.aichallenge.agent.AgentMessage
import ru.koalexse.aichallenge.agent.Role
import ru.koalexse.aichallenge.agent.StatsLLMApi
import ru.koalexse.aichallenge.agent.context.memory.MemoryEntry
import ru.koalexse.aichallenge.agent.context.memory.MemoryLayer
import ru.koalexse.aichallenge.agent.context.memory.MemoryStorage
import ru.koalexse.aichallenge.domain.ApiMessage
import ru.koalexse.aichallenge.domain.ChatRequest
import ru.koalexse.aichallenge.domain.StatsStreamResult

/**
 * Стратегия «Layered Memory» — явная трёхслойная модель памяти ассистента.
 *
 * ## Три слоя памяти
 *
 * | Слой | Что хранит | Триггер обновления | В LLM |
 * |------|-----------|-------------------|-------|
 * | SHORT_TERM | Последние [keepRecentCount] сообщений | Автоматически (скользящее окно) | ✅ как история |
 * | WORKING | Текущая задача, шаги, промежуточные результаты | LLM-вызов при вытеснении сообщений | ✅ как system |
 * | LONG_TERM | Профиль, решения, устойчивые знания | LLM-вызов только по явному запросу | ✅ как system |
 *
 * ## Политика обновления LONG_TERM
 *
 * LONG_TERM **никогда не перезаписывается** — только пополняется:
 * - LLM получает только новый диалог и возвращает **только новые факты**
 * - Из ответа LLM берутся только записи с ключами, которых ещё нет в хранилище
 * - Существующий ключ **всегда побеждает** (два уровня защиты: промпт + код)
 * - Удаление возможно только через [clearAllMemory] (явное действие пользователя)
 *
 * ## Что уходит в LLM-запрос
 * ```
 * [system: "Long-term memory: ..."]    ← из getAdditionalSystemMessages()
 * [system: "Working memory: ..."]      ← из getAdditionalSystemMessages()
 * [M(n-N+1) … Mn]                      ← SHORT_TERM из _context (recent-окно)
 * ```
 *
 * ## Что видно в UI
 * ```
 * 🧠 Long-term memory bubble           ← долговременная память
 * 💼 Working memory bubble             ← рабочая память
 * [M1🗜️ … Mk🗜️]                       ← вытесненные сообщения (только UI)
 * [Mk+1 … Mn]                          ← recent messages
 * ```
 *
 * ## Логика truncate()
 * 1. `recentMessages` = последние [keepRecentCount] → остаются в `_context` и идут в LLM
 * 2. `oldMessages` = всё, что за пределами окна → в `compressedMessages` (только UI)
 * 3. Если `oldMessages.size >= autoRefreshThreshold` → LLM-вызов для WORKING (фоновый)
 * 4. В LLM уходит: long-term + working (как system) + recent (как история)
 *
 * ## Обновление слоёв
 * - **WORKING** — автоматически в [truncate] + вручную через [refreshWorkingMemory]
 * - **LONG_TERM** — только вручную через [refreshLongTermMemory] (кнопка 🧠 в UI)
 *
 * @param api                  API для LLM-вызовов при обновлении памяти
 * @param memoryStorage        хранилище всех трёх слоёв (persisted)
 * @param keepRecentCount      размер SHORT_TERM окна (сообщений в LLM)
 * @param memoryModel          модель, используемая для извлечения памяти
 * @param tokenEstimator       функция оценки токенов
 * @param autoRefreshThreshold минимальный размер вытесняемого блока для автообновления WORKING
 */
class LayeredMemoryStrategy(
    private val api: StatsLLMApi,
    private val memoryStorage: MemoryStorage,
    val keepRecentCount: Int = DEFAULT_KEEP_RECENT,
    private val memoryModel: String,
    private val tokenEstimator: TokenEstimator = TokenEstimators.default,
    val autoRefreshThreshold: Int = DEFAULT_AUTO_REFRESH_THRESHOLD
) : ContextTruncationStrategy {

    init {
        require(keepRecentCount > 0) { "keepRecentCount must be positive" }
        require(autoRefreshThreshold > 0) { "autoRefreshThreshold must be positive" }
    }

    // ==================== ContextTruncationStrategy ====================

    /**
     * Обрезает историю для LLM-запроса.
     *
     * Сообщения за пределами [keepRecentCount] накапливаются в [memoryStorage]
     * как compressed-сообщения (только UI).
     *
     * Если вытесняемый блок ≥ [autoRefreshThreshold] → фоновый LLM-вызов для WORKING.
     * Ошибка проглатывается — основной поток не ломается.
     *
     * LONG_TERM автоматически не обновляется — только по явному запросу пользователя.
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
            val alreadyCompressed = memoryStorage.getCompressedMessages()
            memoryStorage.setCompressedMessages(alreadyCompressed + oldMessages)

            if (oldMessages.size >= autoRefreshThreshold) {
                try {
                    refreshWorkingMemory(oldMessages)
                } catch (_: Exception) { /* фоновый вызов, ошибка не критична */ }
            }

            var result = recentMessages
            if (maxMessages != null && result.size > maxMessages) result = result.takeLast(maxMessages)
            if (maxTokens != null) result = TruncationUtils.truncateByTokens(result, maxTokens, tokenEstimator)
            return result
        }

        var result = messages
        if (maxMessages != null && result.size > maxMessages) result = result.takeLast(maxMessages)
        if (maxTokens != null) result = TruncationUtils.truncateByTokens(result, maxTokens, tokenEstimator)
        return result
    }

    /**
     * Возвращает до двух system-сообщений для LLM-запроса:
     * 1. Long-term memory (профиль, знания) — если не пуст
     * 2. Working memory (текущая задача, шаги) — если не пуст
     */
    override suspend fun getAdditionalSystemMessages(): List<AgentMessage> {
        val longTerm = memoryStorage.getLongTerm()
        val working  = memoryStorage.getWorking()
        val result   = mutableListOf<AgentMessage>()

        if (longTerm.isNotEmpty()) {
            result += AgentMessage(
                role = Role.SYSTEM,
                content = buildString {
                    appendLine("Long-term memory (user profile, decisions, knowledge):")
                    longTerm.forEach { appendLine("- ${it.key}: ${it.value}") }
                }.trimEnd()
            )
        }

        if (working.isNotEmpty()) {
            result += AgentMessage(
                role = Role.SYSTEM,
                content = buildString {
                    appendLine("Working memory (current task, steps, intermediate results):")
                    working.forEach { appendLine("- ${it.key}: ${it.value}") }
                }.trimEnd()
            )
        }

        return result
    }

    /**
     * Очищает рабочую память и compressed-сообщения.
     * Долговременная память **намеренно не очищается** при сбросе сессии.
     */
    override suspend fun clear() = memoryStorage.clearSession()

    // ==================== Capability: чтение ====================

    suspend fun getWorkingMemory(): List<MemoryEntry>  = memoryStorage.getWorking()
    suspend fun getLongTermMemory(): List<MemoryEntry> = memoryStorage.getLongTerm()
    suspend fun getCompressedMessages(): List<AgentMessage> = memoryStorage.getCompressedMessages()
    suspend fun loadCompressedMessages(messages: List<AgentMessage>) =
        memoryStorage.setCompressedMessages(messages)

    // ==================== Capability: очистка ====================

    /**
     * Очищает только WORKING-слой памяти.
     *
     * Вызывается из [TaskStateMachineAgent] при переходе между фазами:
     * данные завершённой фазы устарели, итог уже зафиксирован в [PhaseOutput].
     * LONG_TERM и compressed-сообщения не затрагиваются.
     */
    suspend fun clearWorkingMemory() = memoryStorage.replaceWorking(emptyList())

    /**
     * Полная очистка всех слоёв памяти, включая долговременную.
     * Вызывается только по явному запросу пользователя «сбросить всё».
     */
    suspend fun clearAllMemory() = memoryStorage.clearAll()

    // ==================== Memory refresh ====================

    /**
     * LLM-вызов для обновления WORKING-памяти.
     *
     * Два сценария:
     * - **Авто** (внутри [truncate]): `history` = вытесняемые `oldMessages`
     * - **Ручной** (кнопка 💼): `history` = текущая active-история
     *
     * LLM получает существующие записи WORKING + новый диалог → мёрджит → заменяет.
     * WORKING допускает полную замену (задача могла смениться).
     *
     * @return обновлённый список записей WORKING-памяти
     */
    suspend fun refreshWorkingMemory(history: List<AgentMessage>): List<MemoryEntry> =
        withContext(Dispatchers.IO) {
            if (history.isEmpty()) return@withContext memoryStorage.getWorking()

            val request = buildMemoryRequest(
                systemPrompt     = WORKING_MEMORY_EXTRACTION_PROMPT,
                existingText     = buildExistingEntriesText(memoryStorage.getWorking()),
                conversationText = buildConversationText(history)
            )
            val newEntries = parseMemoryResponse(collectLLMResponse(request), MemoryLayer.WORKING)
            memoryStorage.replaceWorking(newEntries)
            newEntries
        }

    /**
     * LLM-вызов для **пополнения** LONG_TERM-памяти новыми фактами.
     *
     * Вызывается **только вручную** (кнопка 🧠) — долговременная память не обновляется
     * автоматически, чтобы избежать нежелательных изменений профиля.
     *
     * ## Политика «только добавление»
     * - LLM видит текущий диалог и возвращает **только новые факты** (не существующие)
     * - Из ответа берутся только записи с ключами, которых ещё нет в хранилище
     * - Существующий ключ **всегда побеждает** (два уровня: промпт + [mergeAppendOnly])
     * - Удаление записей возможно только через [clearAllMemory]
     *
     * @return актуальный список всех записей LONG_TERM после пополнения
     */
    suspend fun refreshLongTermMemory(history: List<AgentMessage>): List<MemoryEntry> =
        withContext(Dispatchers.IO) {
            if (history.isEmpty()) return@withContext memoryStorage.getLongTerm()

            val existing = memoryStorage.getLongTerm()

            val request = buildMemoryRequest(
                systemPrompt     = LONG_TERM_MEMORY_EXTRACTION_PROMPT,
                existingText     = buildExistingEntriesText(existing),
                conversationText = buildConversationText(history)
            )
            val llmEntries = parseMemoryResponse(collectLLMResponse(request), MemoryLayer.LONG_TERM)

            // Второй уровень защиты: даже если LLM вернул существующий ключ — игнорируем его
            memoryStorage.appendLongTerm(llmEntries)
            memoryStorage.getLongTerm()
        }

    // ==================== Private ====================

    private fun buildConversationText(history: List<AgentMessage>): String =
        history.joinToString("\n") { msg ->
            val role = when (msg.role) {
                Role.USER      -> "User"
                Role.ASSISTANT -> "Assistant"
                Role.SYSTEM    -> "System"
            }
            "$role: ${msg.content}"
        }

    private fun buildExistingEntriesText(entries: List<MemoryEntry>): String =
        if (entries.isEmpty()) "No entries yet."
        else entries.joinToString("\n") { "- ${it.key}: ${it.value}" }

    private fun buildMemoryRequest(
        systemPrompt: String,
        existingText: String,
        conversationText: String
    ): ChatRequest = ChatRequest(
        messages = listOf(
            ApiMessage(role = "system", content = systemPrompt),
            ApiMessage(
                role = "user",
                content = buildString {
                    appendLine("Existing entries:")
                    appendLine(existingText)
                    appendLine()
                    appendLine("Recent conversation:")
                    append(conversationText)
                }
            )
        ),
        model = memoryModel,
        temperature = 0.2f,
        max_tokens = 800L
    )

    private suspend fun collectLLMResponse(request: ChatRequest): String {
        val builder = StringBuilder()
        api.sendMessageStream(request).collect { result ->
            if (result is StatsStreamResult.Content) builder.append(result.text)
        }
        return builder.toString().trim()
    }

    /**
     * Парсит ответ LLM формата `key: value` (одна запись на строку).
     * `NO_ENTRIES` → пустой список.
     */
    private fun parseMemoryResponse(response: String, layer: MemoryLayer): List<MemoryEntry> {
        if (response.isBlank() || response.equals("NO_ENTRIES", ignoreCase = true)) return emptyList()
        val now = System.currentTimeMillis()
        return response.lines().mapNotNull { line ->
            val cleaned  = line.trimStart('-', '*', '•', ' ')
            val colonIdx = cleaned.indexOf(':')
            if (colonIdx > 0) {
                val key   = cleaned.substring(0, colonIdx).trim()
                val value = cleaned.substring(colonIdx + 1).trim()
                if (key.isNotEmpty() && value.isNotEmpty())
                    MemoryEntry(key = key, value = value, layer = layer, updatedAt = now)
                else null
            } else null
        }
    }

    companion object {
        const val DEFAULT_KEEP_RECENT = 10
        const val DEFAULT_AUTO_REFRESH_THRESHOLD = 2

        /** Промпт для WORKING — допускает полный мёрдж (задача могла смениться). */
        private const val WORKING_MEMORY_EXTRACTION_PROMPT =
            """You are a working memory extractor for an AI assistant. Your task is to maintain a key-value list of the CURRENT TASK data from the conversation.

Focus on:
- Current task goal and subgoals
- Task steps and their status (pending/done/in-progress)
- Intermediate results and computed values
- Active constraints and requirements
- Open questions or blockers

Instructions:
- Merge with existing entries — update changed values, keep relevant ones, remove completed/outdated ones
- Keep entries concise (one line per entry)
- Use short descriptive keys (e.g. "current task", "step 1 status", "result variable X")
- Write in the same language as the conversation
- If a task is fully completed, you may clear task-specific entries

Respond ONLY with entries in this format (one per line):
key: value

If there is nothing task-specific to remember, respond with exactly: NO_ENTRIES"""

        /**
         * Промпт для LONG_TERM — только добавление новых фактов.
         *
         * Ключевые инструкции:
         * - Смотри ТОЛЬКО на новый разговор, существующие записи — только для справки
         * - Возвращай ТОЛЬКО факты, которых ещё нет в существующих записях
         * - НЕ повторяй и НЕ изменяй существующие записи
         * - Если ничего нового — NO_ENTRIES
         */
        private const val LONG_TERM_MEMORY_EXTRACTION_PROMPT =
            """You are a long-term memory extractor for an AI assistant. Your task is to find NEW persistent facts about the user that are NOT already in the existing entries.

Focus on NEW facts about:
- User profile (name, role, expertise level)
- Stable preferences (language, style, tools, frameworks)
- Important decisions made by the user
- Long-term goals and projects
- Recurring patterns and preferences

STRICT instructions:
- Look ONLY at the recent conversation for new facts
- The existing entries are shown for reference ONLY — do NOT repeat, modify or remove them
- Return ONLY facts with keys that do NOT exist in the existing entries
- Be conservative — only extract what is clearly and explicitly stated
- Keep entries concise (one line per entry)
- Use short descriptive keys (e.g. "name", "preferred language", "main project")
- Write in the same language as the conversation

Respond ONLY with NEW entries in this format (one per line):
key: value

If there are no new facts to add, respond with exactly: NO_ENTRIES"""
    }
}
