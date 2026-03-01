package ru.koalexse.aichallenge.data.persistence

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.koalexse.aichallenge.agent.AgentMessage
import ru.koalexse.aichallenge.agent.Role
import ru.koalexse.aichallenge.agent.context.facts.Fact
import ru.koalexse.aichallenge.agent.context.facts.FactsStorage
import java.io.File

/**
 * Данные, хранимые в facts.json.
 *
 * @param version версия схемы (для миграций)
 * @param facts список фактов
 * @param compressedMessages сообщения, вытесненные из LLM-контекста —
 *   только для UI, в LLM не отправляются
 */
private data class FactsStorageData(
    val version: Int = 2,
    val facts: List<PersistedFact> = emptyList(),
    val compressedMessages: List<PersistedAgentMessage> = emptyList()
)

/** Persisted-модель одного факта */
data class PersistedFact(
    val key: String,
    val value: String,
    val updatedAt: Long
)

/**
 * JSON-реализация [FactsStorage].
 *
 * Хранит факты и compressed-сообщения в `facts.json` в приватной директории приложения.
 * Compressed-сообщения — это сообщения, вытесненные из LLM-контекста стратегией
 * [ru.koalexse.aichallenge.agent.context.strategy.StickyFactsStrategy]; они используются
 * только для отображения в UI с пометкой «сжатые».
 *
 * Потокобезопасна через [Mutex].
 */
class JsonFactsStorage(
    private val context: Context,
    private val fileName: String = "facts.json"
) : FactsStorage {

    private val mutex = Mutex()
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val file: File get() = File(context.filesDir, fileName)

    private var cachedFacts: MutableList<Fact>? = null
    private var cachedCompressed: MutableList<AgentMessage>? = null

    // ==================== FactsStorage ====================

    override suspend fun getFacts(): List<Fact> = mutex.withLock {
        ensureLoaded()
        cachedFacts!!.toList()
    }

    override suspend fun replaceFacts(facts: List<Fact>) {
        mutex.withLock {
            ensureLoaded()
            cachedFacts = facts.toMutableList()
            saveLocked()
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            cachedFacts = mutableListOf()
            cachedCompressed = mutableListOf()
            saveLocked()
        }
    }

    override suspend fun getCompressedMessages(): List<AgentMessage> = mutex.withLock {
        ensureLoaded()
        cachedCompressed!!.toList()
    }

    override suspend fun setCompressedMessages(messages: List<AgentMessage>) {
        mutex.withLock {
            ensureLoaded()
            cachedCompressed = messages.toMutableList()
            saveLocked()
        }
    }

    // ==================== Private ====================

    /** Вызывается внутри [mutex]. Ленивая загрузка с диска при первом обращении. */
    private suspend fun ensureLoaded() {
        if (cachedFacts != null && cachedCompressed != null) return
        withContext(Dispatchers.IO) {
            try {
                if (file.exists()) {
                    val data = gson.fromJson(file.readText(), FactsStorageData::class.java)
                    cachedFacts = data?.facts
                        ?.map { it.toDomain() }?.toMutableList() ?: mutableListOf()
                    cachedCompressed = data?.compressedMessages
                        ?.map { it.toAgentMessage() }?.toMutableList() ?: mutableListOf()
                } else {
                    cachedFacts = mutableListOf()
                    cachedCompressed = mutableListOf()
                }
            } catch (_: Exception) {
                cachedFacts = mutableListOf()
                cachedCompressed = mutableListOf()
            }
        }
    }

    /** Вызывается внутри [mutex]. Сохраняет текущий снимок на диск. */
    private suspend fun saveLocked() {
        val snapshotFacts = cachedFacts!!.toList()
        val snapshotCompressed = cachedCompressed!!.toList()
        withContext(Dispatchers.IO) {
            try {
                val data = FactsStorageData(
                    facts = snapshotFacts.map { it.toPersisted() },
                    compressedMessages = snapshotCompressed.map { it.toPersisted() }
                )
                file.writeText(gson.toJson(data))
            } catch (_: Exception) { /* не ломаем UX */ }
        }
    }

    // ==================== Mappers ====================

    private fun Fact.toPersisted() = PersistedFact(key, value, updatedAt)
    private fun PersistedFact.toDomain() = Fact(key, value, updatedAt)

    private fun AgentMessage.toPersisted() = PersistedAgentMessage(
        role = role.name.lowercase(),
        content = content,
        timestamp = timestamp
    )

    private fun PersistedAgentMessage.toAgentMessage() = AgentMessage(
        role = when (role.lowercase()) {
            "user"      -> Role.USER
            "assistant" -> Role.ASSISTANT
            else        -> Role.SYSTEM
        },
        content = content,
        timestamp = timestamp
    )
}
