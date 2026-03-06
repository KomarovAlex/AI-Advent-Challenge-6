package ru.koalexse.aichallenge.agent.task

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Хранилище состояния задачи.
 *
 * Персистирует [TaskState] — фазу автомата, текущий шаг, ожидаемое действие,
 * инварианты по фазам, счётчик повторов и историю завершённых задач.
 *
 * Реализации должны быть потокобезопасными.
 */
interface TaskStateStorage {

    /** Возвращает текущее состояние задачи. */
    suspend fun getState(): TaskState

    /** Сохраняет новое состояние задачи. */
    suspend fun saveState(state: TaskState)

    /** Сбрасывает состояние до начального (isActive = false), сохраняя архив. */
    suspend fun reset()
}

/**
 * In-memory реализация [TaskStateStorage] — данные не переживают перезапуск.
 * Используется для тестов или как заглушка.
 */
class InMemoryTaskStateStorage : TaskStateStorage {

    private val mutex = Mutex()

    @Volatile
    private var _state: TaskState = TaskState()

    override suspend fun getState(): TaskState = _state

    override suspend fun saveState(state: TaskState) {
        mutex.withLock { _state = state }
    }

    override suspend fun reset() {
        mutex.withLock { _state = TaskState() }
    }
}
