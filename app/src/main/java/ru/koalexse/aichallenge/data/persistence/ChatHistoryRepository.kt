package ru.koalexse.aichallenge.data.persistence

/**
 * Интерфейс репозитория для хранения истории чата
 */
interface ChatHistoryRepository {
    
    /**
     * Сохраняет сессию чата
     * Если сессия с таким ID уже существует, она будет перезаписана
     * 
     * @param session сессия для сохранения
     */
    suspend fun saveSession(session: ChatSession)
    
    /**
     * Загружает сессию по ID
     * 
     * @param sessionId ID сессии
     * @return сессия или null, если не найдена
     */
    suspend fun loadSession(sessionId: String): ChatSession?
    
    /**
     * Загружает последнюю (самую свежую) сессию
     * 
     * @return последняя сессия или null, если сессий нет
     */
    suspend fun loadLatestSession(): ChatSession?
    
    /**
     * Загружает активную сессию (помеченную как текущая)
     * Если активная сессия не задана, возвращает последнюю
     * 
     * @return активная сессия или null
     */
    suspend fun loadActiveSession(): ChatSession?
    
    /**
     * Устанавливает активную сессию
     * 
     * @param sessionId ID сессии, которую нужно сделать активной
     */
    suspend fun setActiveSession(sessionId: String)
    
    /**
     * Удаляет сессию по ID
     * 
     * @param sessionId ID сессии для удаления
     */
    suspend fun deleteSession(sessionId: String)
    
    /**
     * Возвращает список всех сохранённых сессий
     * Отсортирован по времени обновления (сначала новые)
     * 
     * @return список сессий
     */
    suspend fun getAllSessions(): List<ChatSession>
    
    /**
     * Удаляет все сохранённые сессии
     */
    suspend fun clearAll()
    
    /**
     * Возвращает ID активной сессии
     */
    suspend fun getActiveSessionId(): String?
}
