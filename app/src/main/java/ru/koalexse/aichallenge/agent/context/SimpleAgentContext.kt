package ru.koalexse.aichallenge.agent.context

import ru.koalexse.aichallenge.agent.AgentMessage
import ru.koalexse.aichallenge.agent.Role
import ru.koalexse.aichallenge.agent.context.strategy.ContextTruncationStrategy
import ru.koalexse.aichallenge.agent.context.strategy.SimpleContextTruncationStrategy

/**
 * Простая потокобезопасная реализация контекста агента
 * 
 * Хранит историю сообщений в памяти с возможностью ограничения размера.
 * Все операции синхронизированы для обеспечения потокобезопасности.
 * 
 * @param maxHistorySize максимальный размер истории в сообщениях (null = без ограничений)
 * @param maxTokens максимальный размер истории в токенах (null = без ограничений)
 * @param truncationStrategy стратегия обрезки контекста
 * @param initialHistory начальная история сообщений
 */
class SimpleAgentContext(
    maxHistorySize: Int? = null,
    maxTokens: Int? = null,
    truncationStrategy: ContextTruncationStrategy = SimpleContextTruncationStrategy(),
    initialHistory: List<AgentMessage> = emptyList()
) : AgentContext {
    
    private val lock = Any()
    private val history: MutableList<AgentMessage> = mutableListOf()
    private var _maxHistorySize: Int? = maxHistorySize
    private var _maxTokens: Int? = maxTokens
    private var _truncationStrategy: ContextTruncationStrategy = truncationStrategy
    
    init {
        if (initialHistory.isNotEmpty()) {
            history.addAll(initialHistory)
            applyTruncation()
        }
    }
    
    override val maxHistorySize: Int?
        get() = synchronized(lock) { _maxHistorySize }
    
    override val maxTokens: Int?
        get() = synchronized(lock) { _maxTokens }
    
    override val truncationStrategy: ContextTruncationStrategy
        get() = synchronized(lock) { _truncationStrategy }
    
    override val size: Int
        get() = synchronized(lock) { history.size }
    
    override val isEmpty: Boolean
        get() = synchronized(lock) { history.isEmpty() }
    
    override fun getHistory(): List<AgentMessage> {
        return synchronized(lock) { history.toList() }
    }
    
    override fun getLastMessages(count: Int): List<AgentMessage> {
        require(count >= 0) { "Count must be non-negative" }
        return synchronized(lock) {
            if (count >= history.size) {
                history.toList()
            } else {
                history.takeLast(count)
            }
        }
    }
    
    override fun getMessagesByRole(role: Role): List<AgentMessage> {
        return synchronized(lock) {
            history.filter { it.role == role }
        }
    }
    
    override fun getLastMessage(): AgentMessage? {
        return synchronized(lock) { history.lastOrNull() }
    }
    
    override fun getLastMessageByRole(role: Role): AgentMessage? {
        return synchronized(lock) {
            history.lastOrNull { it.role == role }
        }
    }
    
    override fun addMessage(message: AgentMessage) {
        synchronized(lock) {
            history.add(message)
            applyTruncation()
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
        synchronized(lock) {
            history.addAll(messages)
            applyTruncation()
        }
    }
    
    override fun removeLastMessage(): AgentMessage? {
        return synchronized(lock) {
            if (history.isNotEmpty()) {
                history.removeAt(history.lastIndex)
            } else {
                null
            }
        }
    }
    
    override fun removeLastMessages(count: Int): List<AgentMessage> {
        require(count >= 0) { "Count must be non-negative" }
        return synchronized(lock) {
            val toRemove = minOf(count, history.size)
            val removed = mutableListOf<AgentMessage>()
            repeat(toRemove) {
                history.removeLastOrNull()?.let { removed.add(0, it) }
            }
            removed
        }
    }
    
    override fun clear() {
        synchronized(lock) {
            history.clear()
        }
    }
    
    override fun updateMaxHistorySize(newMaxSize: Int?) {
        synchronized(lock) {
            _maxHistorySize = newMaxSize
            applyTruncation()
        }
    }
    
    override fun updateMaxTokens(newMaxTokens: Int?) {
        synchronized(lock) {
            _maxTokens = newMaxTokens
            applyTruncation()
        }
    }
    
    /**
     * Обновляет стратегию обрезки
     * 
     * @param newStrategy новая стратегия
     */
    fun updateTruncationStrategy(newStrategy: ContextTruncationStrategy) {
        synchronized(lock) {
            _truncationStrategy = newStrategy
            applyTruncation()
        }
    }
    
    override fun copy(): AgentContext {
        return synchronized(lock) {
            SimpleAgentContext(
                maxHistorySize = _maxHistorySize,
                maxTokens = _maxTokens,
                truncationStrategy = _truncationStrategy,
                initialHistory = history.toList()
            )
        }
    }
    
    /**
     * Применяет стратегию обрезки к истории
     * Должен вызываться внутри synchronized блока
     */
    private fun applyTruncation() {
        if (_maxHistorySize == null && _maxTokens == null) return
        
        val truncated = _truncationStrategy.truncate(
            messages = history,
            maxTokens = _maxTokens,
            maxMessages = _maxHistorySize
        )
        
        if (truncated.size != history.size) {
            history.clear()
            history.addAll(truncated)
        }
    }
    
    override fun toString(): String {
        return synchronized(lock) {
            "SimpleAgentContext(size=${history.size}, maxHistorySize=$_maxHistorySize, maxTokens=$_maxTokens)"
        }
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SimpleAgentContext) return false
        return synchronized(lock) {
            synchronized(other.lock) {
                history == other.history && 
                _maxHistorySize == other._maxHistorySize &&
                _maxTokens == other._maxTokens
            }
        }
    }
    
    override fun hashCode(): Int {
        return synchronized(lock) {
            var result = history.hashCode()
            result = 31 * result + (_maxHistorySize ?: 0)
            result = 31 * result + (_maxTokens ?: 0)
            result
        }
    }
}
