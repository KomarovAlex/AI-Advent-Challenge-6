package ru.koalexse.aichallenge.data.persistence

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.koalexse.aichallenge.agent.context.facts.Fact
import ru.koalexse.aichallenge.agent.context.facts.FactsStorage
import java.io.File

private data class FactsStorageData(
    val version: Int = 1,
    val facts: List<PersistedFact> = emptyList()
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
 * Хранит факты в `facts.json` в приватной директории приложения.
 * Потокобезопасна через [Mutex].
 */
class JsonFactsStorage(
    private val context: Context,
    private val fileName: String = "facts.json"
) : FactsStorage {

    private val mutex = Mutex()
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val file: File get() = File(context.filesDir, fileName)

    private var cached: MutableList<Fact>? = null

    override suspend fun getFacts(): List<Fact> = mutex.withLock {
        ensureLoaded()
        cached!!.toList()
    }

    override suspend fun replaceFacts(facts: List<Fact>) {
        mutex.withLock {
            cached = facts.toMutableList()
            saveLocked()
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            cached = mutableListOf()
            saveLocked()
        }
    }

    // Вызывается внутри mutex
    private suspend fun ensureLoaded() {
        if (cached != null) return
        withContext(Dispatchers.IO) {
            cached = try {
                if (file.exists()) {
                    val data = gson.fromJson(file.readText(), FactsStorageData::class.java)
                    data?.facts?.map { it.toDomain() }?.toMutableList() ?: mutableListOf()
                } else mutableListOf()
            } catch (_: Exception) { mutableListOf() }
        }
    }

    // Вызывается внутри mutex
    private suspend fun saveLocked() {
        val snapshot = cached!!.toList()
        withContext(Dispatchers.IO) {
            try {
                val data = FactsStorageData(facts = snapshot.map { it.toPersisted() })
                file.writeText(gson.toJson(data))
            } catch (_: Exception) { /* не ломаем UX */ }
        }
    }

    private fun Fact.toPersisted() = PersistedFact(key, value, updatedAt)
    private fun PersistedFact.toDomain() = Fact(key, value, updatedAt)
}
