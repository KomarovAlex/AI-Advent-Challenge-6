package ru.koalexse.aichallenge.data.persistence

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.koalexse.aichallenge.agent.task.ArchivedTask
import ru.koalexse.aichallenge.agent.task.PhaseInvariants
import ru.koalexse.aichallenge.agent.task.TaskPhase
import ru.koalexse.aichallenge.agent.task.TaskState
import ru.koalexse.aichallenge.agent.task.TaskStateStorage
import java.io.File

// ==================== Persisted models ====================

private data class PersistedTaskState(
    val version: Int = 1,
    val taskId: String = "",
    val phase: String = TaskPhase.PLANNING.name,
    val currentStep: String = "",
    val expectedAction: String = "",
    val phaseInvariants: List<PersistedPhaseInvariants> = emptyList(),
    val isActive: Boolean = false,
    val retryCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val archivedTasks: List<PersistedArchivedTask> = emptyList()
)

private data class PersistedPhaseInvariants(
    val phase: String,
    val rules: List<String>
)

private data class PersistedArchivedTask(
    val taskId: String,
    val summary: String,
    val completedAt: Long
)

// ==================== JsonTaskStateStorage ====================

/**
 * JSON-реализация [TaskStateStorage].
 *
 * Хранит полное состояние задачи (включая архив завершённых) в одном файле:
 * `task_state.json`
 *
 * Файл загружается лениво при первом обращении и кэшируется в памяти.
 * Один [Mutex] защищает все операции.
 */
class JsonTaskStateStorage(private val context: Context) : TaskStateStorage {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val mutex = Mutex()

    private var cached: TaskState? = null

    private val file: File get() = File(context.filesDir, "task_state.json")

    override suspend fun getState(): TaskState = mutex.withLock {
        ensureLoaded()
        cached!!
    }

    override suspend fun saveState(state: TaskState) {
        mutex.withLock {
            cached = state
            saveLocked()
        }
    }

    override suspend fun reset() {
        mutex.withLock {
            // Сохраняем архив при сбросе
            val archive = cached?.archivedTasks ?: emptyList()
            cached = TaskState(archivedTasks = archive)
            saveLocked()
        }
    }

    // ==================== Private ====================

    private suspend fun ensureLoaded() {
        if (cached != null) return
        withContext(Dispatchers.IO) {
            cached = loadFromFile()
        }
    }

    private suspend fun saveLocked() {
        val snapshot = cached!!
        withContext(Dispatchers.IO) {
            saveToFile(snapshot)
        }
    }

    private fun loadFromFile(): TaskState {
        if (!file.exists()) return TaskState()
        return try {
            val persisted = gson.fromJson(file.readText(), PersistedTaskState::class.java)
            persisted?.toDomain() ?: TaskState()
        } catch (_: Exception) {
            TaskState()
        }
    }

    private fun saveToFile(state: TaskState) {
        try {
            val persisted = state.toPersisted()
            file.writeText(gson.toJson(persisted))
        } catch (_: Exception) { /* не ломаем UX */ }
    }

    // ==================== Mappers ====================

    private fun TaskState.toPersisted() = PersistedTaskState(
        taskId = taskId,
        phase = phase.name,
        currentStep = currentStep,
        expectedAction = expectedAction,
        phaseInvariants = phaseInvariants.map { inv ->
            PersistedPhaseInvariants(phase = inv.phase.name, rules = inv.rules)
        },
        isActive = isActive,
        retryCount = retryCount,
        createdAt = createdAt,
        updatedAt = updatedAt,
        archivedTasks = archivedTasks.map { a ->
            PersistedArchivedTask(taskId = a.taskId, summary = a.summary, completedAt = a.completedAt)
        }
    )

    private fun PersistedTaskState.toDomain() = TaskState(
        taskId = taskId,
        phase = runCatching { TaskPhase.valueOf(phase) }.getOrDefault(TaskPhase.PLANNING),
        currentStep = currentStep,
        expectedAction = expectedAction,
        phaseInvariants = phaseInvariants.map { inv ->
            PhaseInvariants(
                phase = runCatching { TaskPhase.valueOf(inv.phase) }.getOrDefault(TaskPhase.PLANNING),
                rules = inv.rules
            )
        },
        isActive = isActive,
        retryCount = retryCount,
        createdAt = createdAt,
        updatedAt = updatedAt,
        archivedTasks = archivedTasks.map { a ->
            ArchivedTask(taskId = a.taskId, summary = a.summary, completedAt = a.completedAt)
        }
    )
}
