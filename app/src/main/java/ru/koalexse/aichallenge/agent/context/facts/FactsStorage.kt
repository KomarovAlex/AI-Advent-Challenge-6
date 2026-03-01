package ru.koalexse.aichallenge.agent.context.facts

/**
 * Хранилище фактов, извлечённых из диалога стратегией [StickyFactsStrategy].
 *
 * Реализации должны быть потокобезопасными.
 */
interface FactsStorage {
    suspend fun getFacts(): List<Fact>
    suspend fun replaceFacts(facts: List<Fact>)
    suspend fun clear()
}

/**
 * In-memory реализация [FactsStorage] — данные не переживают перезапуск.
 * Используется для тестов или как заглушка.
 */
class InMemoryFactsStorage : FactsStorage {
    @Volatile private var _facts: List<Fact> = emptyList()

    override suspend fun getFacts(): List<Fact> = _facts
    override suspend fun replaceFacts(facts: List<Fact>) { _facts = facts }
    override suspend fun clear() { _facts = emptyList() }
}
