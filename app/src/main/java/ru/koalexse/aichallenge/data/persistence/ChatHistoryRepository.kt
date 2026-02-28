package ru.koalexse.aichallenge.data.persistence

/**
 * Интерфейс репозитория для хранения истории чата.
 *
 * Намеренно живёт в data/persistence/ — оперирует persistence-моделями
 * (ChatSession, PersistedAgentMessage), которые являются деталями сериализации.
 * Перенос в domain/ потребовал бы перетащить туда весь ChatSession — это хуже.
 *
 * ViewModel зависит от этого интерфейса напрямую — допустимо для проекта
 * без отдельного use case слоя.
 */
interface ChatHistoryRepository {

    /**
     * Сохраняет сессию чата.
     * Если сессия с таким ID уже существует — перезаписывает.
     */
    suspend fun saveSession(session: ChatSession)

    /**
     * Загружает сессию по ID.
     */
    suspend fun loadSession(sessionId: String): ChatSession?

    /**
     * Загружает последнюю по времени обновления сессию.
     */
    suspend fun loadLatestSession(): ChatSession?

    /**
     * Загружает активную сессию.
     * Если активная не задана — возвращает последнюю.
     */
    suspend fun loadActiveSession(): ChatSession?

    /**
     * Помечает сессию как активную.
     */
    suspend fun setActiveSession(sessionId: String)

    /**
     * Удаляет сессию по ID.
     */
    suspend fun deleteSession(sessionId: String)

    /**
     * Возвращает все сессии, отсортированные по времени обновления (новые первые).
     */
    suspend fun getAllSessions(): List<ChatSession>

    /**
     * Удаляет все сессии.
     */
    suspend fun clearAll()

    /**
     * Возвращает ID активной сессии.
     */
    suspend fun getActiveSessionId(): String?
}
