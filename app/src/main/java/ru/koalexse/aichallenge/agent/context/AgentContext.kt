package ru.koalexse.aichallenge.agent.context

import ru.koalexse.aichallenge.agent.AgentMessage
import ru.koalexse.aichallenge.agent.Role
import ru.koalexse.aichallenge.agent.context.strategy.ContextTruncationStrategy

/**
 * Интерфейс контекста агента для хранения и управления историей диалога
 * 
 * Контекст отвечает за:
 * - Хранение сообщений диалога
 * - Управление размером истории (ограничение количества сообщений и токенов)
 * - Предоставление истории в различных форматах
 * 
 * Реализации должны быть потокобезопасными.
 */
interface AgentContext {
    
    /**
     * Максимальный размер истории в сообщениях (null = без ограничений)
     */
    val maxHistorySize: Int?
    
    /**
     * Максимальный размер истории в токенах (null = без ограничений)
     */
    val maxTokens: Int?
    
    /**
     * Стратегия обрезки контекста
     */
    val truncationStrategy: ContextTruncationStrategy
    
    /**
     * Текущее количество сообщений в истории
     */
    val size: Int
    
    /**
     * Проверяет, пуста ли история
     */
    val isEmpty: Boolean
    
    /**
     * Возвращает неизменяемую копию всей истории сообщений
     */
    fun getHistory(): List<AgentMessage>
    
    /**
     * Возвращает последние N сообщений
     * 
     * @param count количество сообщений
     * @return список последних сообщений (может быть меньше count, если история короче)
     */
    fun getLastMessages(count: Int): List<AgentMessage>
    
    /**
     * Возвращает сообщения определённой роли
     * 
     * @param role роль для фильтрации
     * @return список сообщений с указанной ролью
     */
    fun getMessagesByRole(role: Role): List<AgentMessage>
    
    /**
     * Возвращает последнее сообщение или null, если история пуста
     */
    fun getLastMessage(): AgentMessage?
    
    /**
     * Возвращает последнее сообщение указанной роли или null
     * 
     * @param role роль для поиска
     */
    fun getLastMessageByRole(role: Role): AgentMessage?
    
    /**
     * Добавляет сообщение в историю
     * 
     * Если установлены лимиты и история превышает их,
     * будет применена стратегия обрезки.
     * 
     * @param message сообщение для добавления
     */
    fun addMessage(message: AgentMessage)
    
    /**
     * Добавляет сообщение пользователя
     * 
     * @param content текст сообщения
     * @return созданное сообщение
     */
    fun addUserMessage(content: String): AgentMessage
    
    /**
     * Добавляет сообщение ассистента
     * 
     * @param content текст сообщения
     * @return созданное сообщение
     */
    fun addAssistantMessage(content: String): AgentMessage
    
    /**
     * Добавляет системное сообщение
     * 
     * @param content текст сообщения
     * @return созданное сообщение
     */
    fun addSystemMessage(content: String): AgentMessage
    
    /**
     * Добавляет несколько сообщений в историю
     * 
     * @param messages список сообщений для добавления
     */
    fun addMessages(messages: List<AgentMessage>)
    
    /**
     * Удаляет последнее сообщение из истории
     * 
     * @return удалённое сообщение или null, если история пуста
     */
    fun removeLastMessage(): AgentMessage?
    
    /**
     * Удаляет N последних сообщений
     * 
     * @param count количество сообщений для удаления
     * @return список удалённых сообщений
     */
    fun removeLastMessages(count: Int): List<AgentMessage>
    
    /**
     * Очищает всю историю
     */
    fun clear()
    
    /**
     * Обновляет максимальный размер истории в сообщениях
     * 
     * Если новый размер меньше текущего количества сообщений,
     * будет применена стратегия обрезки.
     * 
     * @param newMaxSize новый максимальный размер (null = без ограничений)
     */
    fun updateMaxHistorySize(newMaxSize: Int?)
    
    /**
     * Обновляет максимальный размер истории в токенах
     * 
     * @param newMaxTokens новый максимальный размер в токенах (null = без ограничений)
     */
    fun updateMaxTokens(newMaxTokens: Int?)
    
    /**
     * Создаёт копию контекста
     * 
     * @return новый независимый экземпляр контекста с теми же данными
     */
    fun copy(): AgentContext
}
