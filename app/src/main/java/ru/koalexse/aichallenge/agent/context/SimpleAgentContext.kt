package ru.koalexse.aichallenge.agent.context

import ru.koalexse.aichallenge.agent.AgentMessage
import ru.koalexse.aichallenge.agent.Role

/**
 * Простая потокобезопасная реализация контекста агента
 * 
 * Хранит историю сообщений в памяти.
 * Все операции синхронизированы для обеспечения потокобезопасности.
 * 
 * Контекст — это простое хранилище сообщений. Стратегия обрезки 
 * и компрессии управляется агентом.
 * 
 * @param initialHistory начальная история сообщений
 */
class SimpleAgentContext(
    initialHistory: List<AgentMessage> = emptyList()
) : AgentContext {
    
    private val history: MutableList<AgentMessage> = mutableListOf()
    
    init {
        if (initialHistory.isNotEmpty()) {
            history.addAll(initialHistory)
        }
    }
    
    override val size: Int
        get() = synchronized(history) { history.size }
    
    override val isEmpty: Boolean
        get() = synchronized(history) { history.isEmpty() }
    
    override fun getHistory(): List<AgentMessage> {
        return synchronized(history) {
            history.toList()
        }
    }
    
    override fun getLastMessages(count: Int): List<AgentMessage> {
        require(count >= 0) { "Count must be non-negative" }
        return synchronized(history) {
            if (count >= history.size) {
                history.toList()
            } else {
                history.takeLast(count)
            }
        }
    }
    
    override fun getMessagesByRole(role: Role): List<AgentMessage> {
        return synchronized(history) {
            history.filter { it.role == role }
        }
    }
    
    override fun getLastMessage(): AgentMessage? {
        return synchronized(history) {
            history.lastOrNull()
        }
    }
    
    override fun getLastMessageByRole(role: Role): AgentMessage? {
        return synchronized(history) {
            history.lastOrNull { it.role == role }
        }
    }
    
    override fun addMessage(message: AgentMessage) {
        synchronized(history) {
            history.add(message)
        }
    }
    
    override fun addUserMessage(content: String): AgentMessage {
        val message = AgentMessage(role = Role.USER, content = content)
        addMessage(message)
        return message
    }
    
    override fun addAssistantMessage(content: String): AgentMessage {
        val message = AgentMessage(role = Role.ASSISTANT, content = content)
        addMessage(message)
        return message
    }
    
    override fun addSystemMessage(content: String): AgentMessage {
        val message = AgentMessage(role = Role.SYSTEM, content = content)
        addMessage(message)
        return message
    }
    
    override fun addMessages(messages: List<AgentMessage>) {
        if (messages.isEmpty()) return
        synchronized(history) {
            history.addAll(messages)
        }
    }
    
    override fun removeLastMessage(): AgentMessage? {
        return synchronized(history) {
            if (history.isNotEmpty()) {
                history.removeAt(history.lastIndex)
            } else {
                null
            }
        }
    }
    
    override fun removeLastMessages(count: Int): List<AgentMessage> {
        require(count >= 0) { "Count must be non-negative" }
        return synchronized(history) {
            val toRemove = minOf(count, history.size)
            val removed = mutableListOf<AgentMessage>()
            repeat(toRemove) {
                history.removeLastOrNull()?.let { removed.add(0, it) }
            }
            removed
        }
    }
    
    override fun clear() {
        synchronized(history) {
            history.clear()
        }
    }
    
    override fun replaceHistory(messages: List<AgentMessage>) {
        synchronized(history) {
            history.clear()
            history.addAll(messages)
        }
    }
    
    override fun copy(): AgentContext {
        return synchronized(history) {
            SimpleAgentContext(initialHistory = history.toList())
        }
    }
    
    override fun toString(): String {
        return synchronized(history) {
            "SimpleAgentContext(size=${history.size})"
        }
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SimpleAgentContext) return false
        return synchronized(history) {
            synchronized(other.history) {
                history == other.history
            }
        }
    }
    
    override fun hashCode(): Int {
        return synchronized(history) {
            history.hashCode()
        }
    }
}
