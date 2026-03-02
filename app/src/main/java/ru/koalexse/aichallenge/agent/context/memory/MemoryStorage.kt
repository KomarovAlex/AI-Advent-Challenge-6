package ru.koalexse.aichallenge.agent.context.memory

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.koalexse.aichallenge.agent.AgentMessage

/**
 * Хранилище трёхслойной памяти ассистента.
 *
 * Три слоя хранятся **отдельно**:
 * - [WORKING]   — данные текущей задачи; очищаются вместе с сессией
 * - [LONG_TERM] — профиль и знания; persist между сессиями, почти не очищаются
 * - compressed  — сообщения, вытесненные из LLM-контекста (только для UI)
 *
 * [SHORT_TERM] не хранится здесь — им управляет [LayeredMemoryStrategy] как
 * скользящим окном `keepRecentCount` через `_context` агента.
 *
 * Реализации должны быть потокобезопасными.
 */
interface MemoryStorage {

    // ==================== Working memory ====================

    /** Возвращает текущие записи рабочей памяти. */
    suspend fun getWorking(): List<MemoryEntry>

    /** Полностью заменяет рабочую память новым списком записей. */
    suspend fun replaceWorking(entries: List<MemoryEntry>)

    // ==================== Long-term memory ====================

    /** Возвращает текущие записи долговременной памяти. */
    suspend fun getLongTerm(): List<MemoryEntry>

    /** Полностью заменяет долговременную память новым списком записей. */
    suspend fun replaceLongTerm(entries: List<MemoryEntry>)

    // ==================== Compressed messages (только UI) ====================

    /**
     * Возвращает сообщения, вытесненные из LLM-контекста при обрезке.
     * Используются только для отображения в UI с пометкой «сжатые».
     */
    suspend fun getCompressedMessages(): List<AgentMessage>

    /**
     * Заменяет список сжатых сообщений.
     * Вызывается из [LayeredMemoryStrategy.truncate] при накоплении сообщений
     * за пределами keepRecentCount.
     */
    suspend fun setCompressedMessages(messages: List<AgentMessage>)

    // ==================== Lifecycle ====================

    /**
     * Очищает рабочую память и сжатые сообщения.
     * Долговременная память **не очищается** — она persist между сессиями.
     * Вызывается из [LayeredMemoryStrategy.clear] при сбросе сессии.
     */
    suspend fun clearSession()

    /**
     * Полная очистка всех слоёв, включая долговременную память.
     * Используется только при явном запросе пользователя «сбросить всё».
     */
    suspend fun clearAll()
}

/**
 * In-memory реализация [MemoryStorage] — данные не переживают перезапуск.
 * Используется для тестов или как заглушка.
 */
class InMemoryMemoryStorage : MemoryStorage {

    private val mutex = Mutex()

    @Volatile private var _working: List<MemoryEntry> = emptyList()
    @Volatile private var _longTerm: List<MemoryEntry> = emptyList()
    @Volatile private var _compressed: List<AgentMessage> = emptyList()

    override suspend fun getWorking(): List<MemoryEntry> = _working

    override suspend fun replaceWorking(entries: List<MemoryEntry>) {
        mutex.withLock { _working = entries }
    }

    override suspend fun getLongTerm(): List<MemoryEntry> = _longTerm

    override suspend fun replaceLongTerm(entries: List<MemoryEntry>) {
        mutex.withLock { _longTerm = entries }
    }

    override suspend fun getCompressedMessages(): List<AgentMessage> = _compressed

    override suspend fun setCompressedMessages(messages: List<AgentMessage>) {
        mutex.withLock { _compressed = messages }
    }

    override suspend fun clearSession() {
        mutex.withLock {
            _working = emptyList()
            _compressed = emptyList()
            // _longTerm — намеренно не трогаем
        }
    }

    override suspend fun clearAll() {
        mutex.withLock {
            _working = emptyList()
            _longTerm = emptyList()
            _compressed = emptyList()
        }
    }
}
