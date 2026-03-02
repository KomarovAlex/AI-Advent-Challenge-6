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
import ru.koalexse.aichallenge.agent.context.memory.MemoryEntry
import ru.koalexse.aichallenge.agent.context.memory.MemoryLayer
import ru.koalexse.aichallenge.agent.context.memory.MemoryStorage
import java.io.File

// ==================== Persisted models ====================

private data class PersistedMemoryFile(
    val version: Int = 1,
    val entries: List<PersistedMemoryEntry> = emptyList()
)

private data class PersistedCompressedFile(
    val version: Int = 1,
    val messages: List<PersistedAgentMessage> = emptyList()
)

private data class PersistedMemoryEntry(
    val key: String,
    val value: String,
    val layer: String,        // MemoryLayer.name()
    val updatedAt: Long
)

// ==================== JsonMemoryStorage ====================

/**
 * JSON-реализация [MemoryStorage].
 *
 * Три слоя хранятся в **отдельных файлах**:
 *
 * | Слой | Файл | Очищается при clearSession? |
 * |------|------|-----------------------------|
 * | WORKING   | `memory_working.json`    | ✅ да |
 * | LONG_TERM | `memory_long_term.json`  | ❌ нет (persist между сессиями) |
 * | Compressed messages | `memory_compressed.json` | ✅ да |
 *
 * Каждый файл загружается лениво при первом обращении и кэшируется в памяти.
 * Потокобезопасность через [Mutex].
 */
class JsonMemoryStorage(private val context: Context) : MemoryStorage {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    // Отдельный Mutex для каждого файла — нет блокировок между независимыми слоями
    private val workingMutex = Mutex()
    private val longTermMutex = Mutex()
    private val compressedMutex = Mutex()

    // Кэши (null = ещё не загружено)
    private var cachedWorking: MutableList<MemoryEntry>? = null
    private var cachedLongTerm: MutableList<MemoryEntry>? = null
    private var cachedCompressed: MutableList<AgentMessage>? = null

    private val workingFile: File get() = File(context.filesDir, "memory_working.json")
    private val longTermFile: File get() = File(context.filesDir, "memory_long_term.json")
    private val compressedFile: File get() = File(context.filesDir, "memory_compressed.json")

    // ==================== Working ====================

    override suspend fun getWorking(): List<MemoryEntry> = workingMutex.withLock {
        ensureWorkingLoaded()
        cachedWorking!!.toList()
    }

    override suspend fun replaceWorking(entries: List<MemoryEntry>) {
        workingMutex.withLock {
            cachedWorking = entries.toMutableList()
            saveWorkingLocked()
        }
    }

    // ==================== Long-term ====================

    override suspend fun getLongTerm(): List<MemoryEntry> = longTermMutex.withLock {
        ensureLongTermLoaded()
        cachedLongTerm!!.toList()
    }

    override suspend fun replaceLongTerm(entries: List<MemoryEntry>) {
        longTermMutex.withLock {
            cachedLongTerm = entries.toMutableList()
            saveLongTermLocked()
        }
    }

    // ==================== Compressed messages ====================

    override suspend fun getCompressedMessages(): List<AgentMessage> = compressedMutex.withLock {
        ensureCompressedLoaded()
        cachedCompressed!!.toList()
    }

    override suspend fun setCompressedMessages(messages: List<AgentMessage>) {
        compressedMutex.withLock {
            cachedCompressed = messages.toMutableList()
            saveCompressedLocked()
        }
    }

    // ==================== Lifecycle ====================

    override suspend fun clearSession() {
        // Working + compressed очищаются, long-term — нет
        workingMutex.withLock {
            cachedWorking = mutableListOf()
            saveWorkingLocked()
        }
        compressedMutex.withLock {
            cachedCompressed = mutableListOf()
            saveCompressedLocked()
        }
    }

    override suspend fun clearAll() {
        workingMutex.withLock {
            cachedWorking = mutableListOf()
            saveWorkingLocked()
        }
        longTermMutex.withLock {
            cachedLongTerm = mutableListOf()
            saveLongTermLocked()
        }
        compressedMutex.withLock {
            cachedCompressed = mutableListOf()
            saveCompressedLocked()
        }
    }

    // ==================== Private: lazy load ====================

    private suspend fun ensureWorkingLoaded() {
        if (cachedWorking != null) return
        withContext(Dispatchers.IO) {
            cachedWorking = loadEntriesFromFile(workingFile).toMutableList()
        }
    }

    private suspend fun ensureLongTermLoaded() {
        if (cachedLongTerm != null) return
        withContext(Dispatchers.IO) {
            cachedLongTerm = loadEntriesFromFile(longTermFile).toMutableList()
        }
    }

    private suspend fun ensureCompressedLoaded() {
        if (cachedCompressed != null) return
        withContext(Dispatchers.IO) {
            cachedCompressed = loadCompressedFromFile(compressedFile).toMutableList()
        }
    }

    // ==================== Private: save ====================

    private suspend fun saveWorkingLocked() {
        val snapshot = cachedWorking!!.toList()
        withContext(Dispatchers.IO) {
            saveEntriesToFile(workingFile, snapshot)
        }
    }

    private suspend fun saveLongTermLocked() {
        val snapshot = cachedLongTerm!!.toList()
        withContext(Dispatchers.IO) {
            saveEntriesToFile(longTermFile, snapshot)
        }
    }

    private suspend fun saveCompressedLocked() {
        val snapshot = cachedCompressed!!.toList()
        withContext(Dispatchers.IO) {
            saveCompressedToFile(compressedFile, snapshot)
        }
    }

    // ==================== Private: file IO ====================

    private fun loadEntriesFromFile(file: File): List<MemoryEntry> {
        if (!file.exists()) return emptyList()
        return try {
            val data = gson.fromJson(file.readText(), PersistedMemoryFile::class.java)
            data?.entries?.map { it.toDomain() } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveEntriesToFile(file: File, entries: List<MemoryEntry>) {
        try {
            val data = PersistedMemoryFile(entries = entries.map { it.toPersisted() })
            file.writeText(gson.toJson(data))
        } catch (_: Exception) { /* не ломаем UX */ }
    }

    private fun loadCompressedFromFile(file: File): List<AgentMessage> {
        if (!file.exists()) return emptyList()
        return try {
            val data = gson.fromJson(file.readText(), PersistedCompressedFile::class.java)
            data?.messages?.map { it.toAgentMessage() } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveCompressedToFile(file: File, messages: List<AgentMessage>) {
        try {
            val data = PersistedCompressedFile(messages = messages.map { it.toPersistedMessage() })
            file.writeText(gson.toJson(data))
        } catch (_: Exception) { /* не ломаем UX */ }
    }

    // ==================== Mappers ====================

    private fun MemoryEntry.toPersisted() = PersistedMemoryEntry(
        key = key,
        value = value,
        layer = layer.name,
        updatedAt = updatedAt
    )

    private fun PersistedMemoryEntry.toDomain() = MemoryEntry(
        key = key,
        value = value,
        layer = runCatching { MemoryLayer.valueOf(layer) }.getOrDefault(MemoryLayer.WORKING),
        updatedAt = updatedAt
    )

    private fun AgentMessage.toPersistedMessage() = PersistedAgentMessage(
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
