package ru.koalexse.aichallenge.agent.context

import ru.koalexse.aichallenge.agent.AgentMessage
import ru.koalexse.aichallenge.agent.Role

/**
 * Интерфейс контекста агента для хранения и управления историей диалога
 * 
 * Контекст отвечает за:
 * - Хранение сообщений диалога
 * - Предоставление истории в различных форматах
 * 
 * Стратегия обрезки и компрессии управляется агентом, а не контекстом,
 * так как это часть бизнес-логики агента.
 * 
 * Реализации должны быть потокобезопасными.
 */
interface AgentContext {
    
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
     * Заменяет историю на новый список сообщений
     * 
     * @param messages новый список сообщений
     */
    fun replaceHistory(messages: List<AgentMessage>)
    
    /**
     * Создаёт копию контекста
     * 
     * @return новый независимый экземпляр контекста с теми же данными
     */
    fun copy(): AgentContext
}
