package ru.koalexse.aichallenge.agent.context.branch

/**
 * Хранилище веток диалога.
 *
 * Реализации должны быть потокобезопасными.
 */
interface BranchStorage {
    suspend fun getBranches(): List<DialogBranch>
    suspend fun getActiveBranchId(): String?
    suspend fun saveBranch(branch: DialogBranch)
    suspend fun setActiveBranch(branchId: String)
    suspend fun clear()
}

/**
 * In-memory реализация [BranchStorage] — для тестов.
 */
class InMemoryBranchStorage : BranchStorage {
    @Volatile private var _branches: List<DialogBranch> = emptyList()
    @Volatile private var _activeId: String? = null

    override suspend fun getBranches(): List<DialogBranch> = _branches
    override suspend fun getActiveBranchId(): String? = _activeId

    override suspend fun saveBranch(branch: DialogBranch) {
        _branches = _branches.filter { it.id != branch.id } + branch
    }

    override suspend fun setActiveBranch(branchId: String) { _activeId = branchId }
    override suspend fun clear() { _branches = emptyList(); _activeId = null }
}
