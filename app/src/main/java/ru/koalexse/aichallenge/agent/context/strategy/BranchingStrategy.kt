package ru.koalexse.aichallenge.agent.context.strategy

import ru.koalexse.aichallenge.agent.AgentMessage
import ru.koalexse.aichallenge.agent.context.branch.BranchStorage
import ru.koalexse.aichallenge.agent.context.branch.DialogBranch
import ru.koalexse.aichallenge.agent.context.summary.ConversationSummary
import java.util.UUID

/**
 * Стратегия «Branching» — ветки диалога.
 *
 * Принцип работы:
 * - При старте автоматически создаётся первая ветка («Branch 1»)
 * - По нажатию «Checkpoint» текущее состояние сохраняется и создаётся новая ветка
 *   (копия истории на момент checkpoint-а), максимум [MAX_BRANCHES] веток
 * - При переключении ветки история полностью заменяется на историю выбранной ветки
 * - Ветки персистируются в [BranchStorage]
 *
 * ### Capability-интерфейсы
 * Для управления ветками из ViewModel используйте приведение типа:
 * ```kotlin
 * (agent.truncationStrategy as? BranchingStrategy)?.getBranches()
 * ```
 *
 * Стратегия сама НЕ обрезает историю (нет лимитов по умолчанию), но
 * поддерживает опциональный [windowSize] для ограничения через токены/сообщения.
 *
 * @param branchStorage   хранилище веток
 * @param windowSize      опциональное ограничение кол-ва сообщений (null = без ограничения)
 * @param tokenEstimator  функция оценки токенов
 */
class BranchingStrategy(
    private val branchStorage: BranchStorage,
    val windowSize: Int? = null,
    private val tokenEstimator: TokenEstimator = TokenEstimators.default
) : ContextTruncationStrategy {

    override suspend fun truncate(
        messages: List<AgentMessage>,
        maxTokens: Int?,
        maxMessages: Int?
    ): List<AgentMessage> {
        if (messages.isEmpty()) return messages

        var result = messages

        val msgLimit = listOfNotNull(windowSize, maxMessages).minOrNull()
        if (msgLimit != null && result.size > msgLimit) {
            result = result.takeLast(msgLimit)
        }

        if (maxTokens != null) {
            result = TruncationUtils.truncateByTokens(result, maxTokens, tokenEstimator)
        }

        return result
    }

    /** Очищает все ветки — реализация [ContextTruncationStrategy.clear]. */
    override suspend fun clear() = clearBranches()

    // ==================== Branch management (called by Agent/ViewModel via cast) ====================

    /**
     * Возвращает список всех веток в порядке создания.
     */
    suspend fun getBranches(): List<DialogBranch> =
        branchStorage.getBranches().sortedBy { it.createdAt }

    /**
     * Возвращает id активной ветки.
     */
    suspend fun getActiveBranchId(): String? = branchStorage.getActiveBranchId()

    /**
     * Инициализирует первую ветку, если хранилище пусто.
     * Вызывается при старте агента.
     *
     * @return id активной ветки (существующей или только что созданной)
     */
    suspend fun ensureInitialized(): String {
        val branches = branchStorage.getBranches()
        if (branches.isNotEmpty()) {
            return branchStorage.getActiveBranchId() ?: branches.first().id
        }
        val firstBranch = DialogBranch(
            id = UUID.randomUUID().toString(),
            name = "Branch 1",
            messages = emptyList()
        )
        branchStorage.saveBranch(firstBranch)
        branchStorage.setActiveBranch(firstBranch.id)
        return firstBranch.id
    }

    /**
     * Сохраняет текущее состояние активной ветки и создаёт новую ветку-копию.
     *
     * @param currentHistory история текущей ветки (для сохранения)
     * @param currentSummaries summaries текущей ветки
     * @return новая ветка, или null если достигнут лимит [MAX_BRANCHES]
     */
    suspend fun createCheckpoint(
        currentHistory: List<AgentMessage>,
        currentSummaries: List<ConversationSummary>
    ): DialogBranch? {
        val branches = branchStorage.getBranches()
        if (branches.size >= MAX_BRANCHES) return null

        saveCurrentBranchIfActive(
            branches = branches,
            currentHistory = currentHistory,
            currentSummaries = currentSummaries
        )

        // Создаём новую ветку как копию текущего состояния
        val newBranchNumber = branches.size + 1
        val newBranch = DialogBranch(
            id = UUID.randomUUID().toString(),
            name = "Branch $newBranchNumber",
            messages = currentHistory,
            summaries = currentSummaries
        )
        branchStorage.saveBranch(newBranch)
        branchStorage.setActiveBranch(newBranch.id)
        return newBranch
    }

    /**
     * Переключается на ветку с [branchId].
     * Сохраняет текущую активную ветку перед переключением.
     *
     * @return ветка, на которую переключились
     */
    suspend fun switchToBranch(
        branchId: String,
        currentHistory: List<AgentMessage>,
        currentSummaries: List<ConversationSummary>
    ): DialogBranch? {
        val branches = branchStorage.getBranches()

        saveCurrentBranchIfActive(
            branches = branches,
            currentHistory = currentHistory,
            currentSummaries = currentSummaries
        )

        branchStorage.setActiveBranch(branchId)
        return branchStorage.getBranches().find { it.id == branchId }
    }

    /**
     * Сохраняет текущую активную ветку без переключения.
     * Вызывается при сохранении истории.
     */
    suspend fun saveActiveBranch(
        currentHistory: List<AgentMessage>,
        currentSummaries: List<ConversationSummary>
    ) {
        saveCurrentBranchIfActive(
            branches = branchStorage.getBranches(),
            currentHistory = currentHistory,
            currentSummaries = currentSummaries
        )
    }

    /** Очищает все ветки. */
    suspend fun clearBranches() = branchStorage.clear()

    // ==================== Private ====================

    /**
     * Находит активную ветку в [branches] и сохраняет её с [currentHistory] / [currentSummaries].
     * No-op если активная ветка не задана или не найдена в списке.
     */
    private suspend fun saveCurrentBranchIfActive(
        branches: List<DialogBranch>,
        currentHistory: List<AgentMessage>,
        currentSummaries: List<ConversationSummary>
    ) {
        val activeId = branchStorage.getActiveBranchId() ?: return
        val activeBranch = branches.find { it.id == activeId } ?: return
        branchStorage.saveBranch(
            activeBranch.copy(
                messages = currentHistory,
                summaries = currentSummaries
            )
        )
    }

    companion object {
        const val MAX_BRANCHES = 5
    }
}
