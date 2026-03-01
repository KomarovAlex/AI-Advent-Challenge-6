package ru.koalexse.aichallenge.agent.context.facts

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.koalexse.aichallenge.agent.AgentMessage

/**
 * Хранилище фактов, извлечённых из диалога стратегией [StickyFactsStrategy].
 *
 * Помимо фактов, хранит список сообщений, удалённых из LLM-контекста при обрезке
 * ([compressedMessages]). Эти сообщения используются **только для UI** — в запрос
 * к LLM они не включаются; туда идут факты через [getAdditionalSystemMessages].
 *
 * Реализации должны быть потокобезопасными.
 */
interface FactsStorage {
    suspend fun getFacts(): List<Fact>
    suspend fun replaceFacts(facts: List<Fact>)
    suspend fun clear()

    /**
     * Возвращает сообщения, вытесненные из LLM-контекста при обрезке.
     * Используются только для отображения в UI (с пометкой «сжатые»).
     */
    suspend fun getCompressedMessages(): List<AgentMessage>

    /**
     * Заменяет список compressed-сообщений.
     * Вызывается из [StickyFactsStrategy.truncate] при каждом накоплении
     * сообщений за пределами [StickyFactsStrategy.keepRecentCount].
     */
    suspend fun setCompressedMessages(messages: List<AgentMessage>)
}

/**
 * In-memory реализация [FactsStorage] — данные не переживают перезапуск.
 * Используется для тестов или как заглушка.
 */
class InMemoryFactsStorage : FactsStorage {

    private val mutex = Mutex()

    @Volatile private var _facts: List<Fact> = emptyList()
    @Volatile private var _compressedMessages: List<AgentMessage> = emptyList()

    override suspend fun getFacts(): List<Fact> = _facts

    override suspend fun replaceFacts(facts: List<Fact>) {
        mutex.withLock { _facts = facts }
    }

    override suspend fun clear() {
        mutex.withLock {
            _facts = emptyList()
            _compressedMessages = emptyList()
        }
    }

    override suspend fun getCompressedMessages(): List<AgentMessage> = _compressedMessages

    override suspend fun setCompressedMessages(messages: List<AgentMessage>) {
        mutex.withLock { _compressedMessages = messages }
    }
}
