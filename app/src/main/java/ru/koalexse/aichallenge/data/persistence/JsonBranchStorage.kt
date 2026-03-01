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
import ru.koalexse.aichallenge.agent.context.branch.BranchStorage
import ru.koalexse.aichallenge.agent.context.branch.DialogBranch
import ru.koalexse.aichallenge.agent.context.summary.ConversationSummary
import java.io.File

// ==================== Persisted models ====================

private data class BranchStorageData(
    val version: Int = 1,
    val branches: List<PersistedBranch> = emptyList(),
    val activeBranchId: String? = null
)

private data class PersistedBranch(
    val id: String,
    val name: String,
    val messages: List<PersistedAgentMessage>,
    val summaries: List<PersistedSummary> = emptyList(),
    val createdAt: Long
)

// ==================== Repository ====================

/**
 * JSON-реализация [BranchStorage].
 *
 * Хранит ветки в `branches.json` в приватной директории приложения.
 * Потокобезопасна через [Mutex].
 */
class JsonBranchStorage(
    private val context: Context,
    private val fileName: String = "branches.json"
) : BranchStorage {

    private val mutex = Mutex()
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val file: File get() = File(context.filesDir, fileName)

    private var cached: BranchStorageData? = null

    override suspend fun getBranches(): List<DialogBranch> = mutex.withLock {
        ensureLoaded()
        cached!!.branches.map { it.toDomain() }
    }

    override suspend fun getActiveBranchId(): String? = mutex.withLock {
        ensureLoaded()
        cached!!.activeBranchId
    }

    override suspend fun saveBranch(branch: DialogBranch) {
        mutex.withLock {
            ensureLoaded()
            val updated = cached!!.branches.filter { it.id != branch.id } + branch.toPersisted()
            cached = cached!!.copy(branches = updated)
            saveLocked()
        }
    }

    override suspend fun setActiveBranch(branchId: String) {
        mutex.withLock {
            ensureLoaded()
            cached = cached!!.copy(activeBranchId = branchId)
            saveLocked()
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            cached = BranchStorageData()
            saveLocked()
        }
    }

    // ==================== Internal ====================

    private suspend fun ensureLoaded() {
        if (cached != null) return
        withContext(Dispatchers.IO) {
            cached = try {
                if (file.exists()) {
                    gson.fromJson(file.readText(), BranchStorageData::class.java)
                        ?: BranchStorageData()
                } else BranchStorageData()
            } catch (_: Exception) { BranchStorageData() }
        }
    }

    private suspend fun saveLocked() {
        val snapshot = cached!!
        withContext(Dispatchers.IO) {
            try { file.writeText(gson.toJson(snapshot)) } catch (_: Exception) {}
        }
    }

    // ==================== Mappers ====================

    private fun DialogBranch.toPersisted() = PersistedBranch(
        id = id,
        name = name,
        messages = messages.map {
            PersistedAgentMessage(it.role.name, it.content, it.timestamp)
        },
        summaries = summaries.map { s ->
            PersistedSummary(
                content = s.content,
                originalMessages = s.originalMessages.map {
                    PersistedAgentMessage(it.role.name, it.content, it.timestamp)
                },
                createdAt = s.createdAt
            )
        },
        createdAt = createdAt
    )

    private fun PersistedBranch.toDomain() = DialogBranch(
        id = id,
        name = name,
        messages = messages.map {
            AgentMessage(Role.valueOf(it.role), it.content, it.timestamp)
        },
        summaries = summaries.map { s ->
            ConversationSummary(
                content = s.content,
                originalMessages = s.originalMessages.map {
                    AgentMessage(Role.valueOf(it.role), it.content, it.timestamp)
                },
                createdAt = s.createdAt
            )
        },
        createdAt = createdAt
    )
}
