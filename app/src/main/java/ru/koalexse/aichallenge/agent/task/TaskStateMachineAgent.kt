package ru.koalexse.aichallenge.agent.task

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import ru.koalexse.aichallenge.agent.AgentMessage
import ru.koalexse.aichallenge.agent.AgentRequest
import ru.koalexse.aichallenge.agent.AgentStreamEvent
import ru.koalexse.aichallenge.agent.ConfigurableAgent
import ru.koalexse.aichallenge.agent.Role
import ru.koalexse.aichallenge.agent.StatsLLMApi
import ru.koalexse.aichallenge.agent.context.strategy.LayeredMemoryStrategy
import ru.koalexse.aichallenge.domain.ApiMessage
import ru.koalexse.aichallenge.domain.ChatRequest
import ru.koalexse.aichallenge.domain.StatsStreamResult
import java.util.UUID

/**
 * Агент с формализованным состоянием задачи (Task State Machine).
 *
 * ## Архитектура
 *
 * `TaskStateMachineAgent` — самостоятельная сущность-обёртка над [ConfigurableAgent].
 * Делегирует все операции [Agent] внутреннему [innerAgent] через `by innerAgent`,
 * переопределяя только [send] / [chatStream] для встраивания логики автомата.
 *
 * ## Поток данных
 *
 * ```
 * Пользовательский запрос
 *         ↓
 * buildSystemPromptBlock(state)  ← profile + completed phases + task state + invariants
 *         ↓
 * innerAgent.send()              ← основной LLM-вызов
 *         ↓
 * parseSignal(response)          ← ищем [PHASE_COMPLETE] / [PHASE: X] / [STEP: X] / [EXPECTED: X]
 * extractPhaseOutput(response)   ← ищем [OUTPUT: <итог фазы>]
 *         ↓
 * validateWithLLM()              ← отдельный LLM-вызов: «инварианты соблюдены?»
 *     ↓ SUCCESS                        ↓ FAILURE (до maxRetries)
 * emit + updateState()           retry с violations в промпте
 *         ↓
 * transition(signal)             ← обновление фазы/шага/expected + сохранение PhaseOutput
 *         ↓
 * clearWorkingMemoryIfNeeded()   ← сброс WORKING-памяти при смене фазы
 *         ↓
 * taskStateStorage.save()        ← персистентность между сессиями
 * ```
 *
 * ## Фазы автомата
 * ```
 * PLANNING → EXECUTION → VALIDATION → DONE → (новая задача)
 * ```
 *
 * ## Разрешённые переходы
 * Единственный разрешённый переход из фазы F — строго в F.next().
 * Любой сигнал [TaskSignal.SuggestPhase], указывающий на фазу, отличную
 * от F.next(), молча игнорируется — состояние не меняется.
 * Проверка централизована в [isTransitionAllowed] и используется во всех
 * точках, где происходит смена фазы.
 *
 * ## Передача данных между фазами
 * При переходе фазы агент:
 * 1. Извлекает итог текущей фазы из тега `[OUTPUT: <текст>]` (или берёт fallback)
 * 2. Сохраняет его в [TaskState.phaseOutputs]
 * 3. Очищает WORKING-память ([LayeredMemoryStrategy]) — старые данные фазы уже не нужны
 * Следующая фаза получает итоги всех завершённых фаз в секции `## Completed phases`
 * system-промпта — даже если они вышли за пределы скользящего окна истории.
 *
 * ## Пауза и продолжение
 * При перезапуске приложения [taskStateStorage] восстанавливает состояние,
 * LLM получает актуальный task-блок в system-промпте — пользователь продолжает
 * без повторных объяснений.
 *
 * @param innerAgent         внутренний агент (с SummaryTruncationStrategy по умолчанию)
 * @param api                API для LLM-вызовов при валидации и архивировании
 * @param taskStateStorage   хранилище состояния задачи (persist → task_state.json)
 * @param taskModel          модель для вызовов валидации и архивирования
 * @param maxRetries         максимальное число повторных попыток при нарушении инвариантов
 */
class TaskStateMachineAgent(
    private val innerAgent: ConfigurableAgent,
    private val api: StatsLLMApi,
    val taskStateStorage: TaskStateStorage,
    private val taskModel: String,
    val maxRetries: Int = DEFAULT_MAX_RETRIES
) : ConfigurableAgent by innerAgent {

    // ==================== Переопределение send / chatStream ====================

    /**
     * Отправляет сообщение пользователя с учётом состояния задачи.
     *
     * Если активной задачи нет — работает как обычный агент (без task-блока).
     * Если задача активна — встраивает task-блок в system-промпт и запускает
     * цикл валидации.
     */
    override suspend fun send(message: String): Flow<AgentStreamEvent> {
        val state = taskStateStorage.getState()
        return if (!state.isActive) {
            innerAgent.send(message)
        } else {
            sendWithTaskState(message, state)
        }
    }

    override suspend fun chatStream(request: AgentRequest): Flow<AgentStreamEvent> {
        val state = taskStateStorage.getState()
        return if (!state.isActive) {
            innerAgent.chatStream(request)
        } else {
            val augmented = request.copy(
                systemPrompt = buildSystemPromptBlock(state, request.systemPrompt)
            )
            sendWithValidation(augmented, state)
        }
    }

    // ==================== Task lifecycle ====================

    /**
     * Запускает новую задачу.
     *
     * Создаёт [TaskState] с фазой [TaskPhase.PLANNING], задаёт инварианты по фазам
     * из пользовательского ввода и персистирует состояние.
     *
     * @param phaseInvariants инварианты, введённые пользователем (может быть пустым)
     * @return созданное состояние задачи
     */
    suspend fun startTask(phaseInvariants: List<PhaseInvariants>): TaskState {
        val taskId = UUID.randomUUID().toString()
        val state = TaskState(
            taskId = taskId,
            phase = TaskPhase.PLANNING,
            currentStep = "Определение цели и декомпозиция задачи",
            expectedAction = "Опишите задачу, которую нужно решить",
            phaseInvariants = phaseInvariants,
            isActive = true,
            retryCount = 0,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        taskStateStorage.saveState(state)
        return state
    }

    /**
     * Ручной переход к следующей фазе с LLM-валидацией готовности.
     *
     * LLM проверяет: «достаточно ли сделано в текущей фазе для перехода?»
     * Если нет — возвращает [AdvancePhaseResult.NotReady] с объяснением.
     * Если да — сохраняет [PhaseOutput] текущей фазы, очищает WORKING-память
     * и выполняет переход.
     *
     * @return результат попытки перехода
     */
    suspend fun advancePhase(): AdvancePhaseResult = withContext(Dispatchers.IO) {
        val state = taskStateStorage.getState()
        if (!state.isActive) return@withContext AdvancePhaseResult.NoActiveTask

        val nextPhase = state.phase.next() ?: return@withContext AdvancePhaseResult.AlreadyDone

        val history = innerAgent.conversationHistory.takeLast(VALIDATION_HISTORY_WINDOW)
        val readinessResult = checkPhaseReadiness(state, history)

        if (!readinessResult.isValid) {
            return@withContext AdvancePhaseResult.NotReady(readinessResult.violations)
        }

        // Фиксируем итог текущей фазы из последнего ответа ассистента
        val lastAssistantMessage = history.lastOrNull { it.role == Role.ASSISTANT }?.content ?: ""
        val newState = transitionToPhase(
            state = state,
            next = nextPhase,
            fullResponse = lastAssistantMessage,
            now = System.currentTimeMillis()
        )

        if (nextPhase == TaskPhase.DONE) {
            val archived = archiveTask(state, history)
            val finalState = newState.copy(archivedTasks = archived)
            taskStateStorage.saveState(finalState)
            return@withContext AdvancePhaseResult.Advanced(finalState)
        }

        taskStateStorage.saveState(newState)
        AdvancePhaseResult.Advanced(newState)
    }

    /**
     * Сбрасывает текущую задачу (архивирует если была активна).
     * После сброса [TaskState.isActive] = false, [TaskState.phaseOutputs] очищаются.
     */
    suspend fun resetTask(): TaskState = withContext(Dispatchers.IO) {
        val current = taskStateStorage.getState()
        if (current.isActive && current.taskId.isNotEmpty()) {
            val archived = ArchivedTask(
                taskId = current.taskId,
                summary = "Сброшена вручную на этапе ${current.phase.name}: ${current.currentStep}",
                completedAt = System.currentTimeMillis()
            )
            // phaseOutputs не переносим — они принадлежат только этой задаче
            val newState = TaskState(
                archivedTasks = current.archivedTasks + archived
            )
            taskStateStorage.saveState(newState)
            newState
        } else {
            taskStateStorage.reset()
            taskStateStorage.getState()
        }
    }

    /** Возвращает текущее состояние задачи. */
    suspend fun getTaskState(): TaskState = taskStateStorage.getState()

    // ==================== Private: поток с автоматом ====================

    private suspend fun sendWithTaskState(
        message: String,
        state: TaskState
    ): Flow<AgentStreamEvent> {
        val config = innerAgent.config
        val request = AgentRequest(
            userMessage = message,
            systemPrompt = buildSystemPromptBlock(state, config.defaultSystemPrompt),
            model = config.defaultModel,
            temperature = config.defaultTemperature,
            maxTokens = config.defaultMaxTokens,
            stopSequences = config.defaultStopSequences
        )
        return sendWithValidation(request, state)
    }

    /**
     * Основной цикл:
     * 1. LLM-вызов через innerAgent
     * 2. Накопление полного ответа
     * 3. Парсинг сигнала перехода + извлечение PhaseOutput
     * 4. LLM-валидация инвариантов
     * 5a. SUCCESS → emit stream + updateState + transition
     * 5b. FAILURE → retry с violations (до maxRetries)
     */
    private fun sendWithValidation(
        request: AgentRequest,
        state: TaskState
    ): Flow<AgentStreamEvent> = flow {
        var currentState = state
        var attempt = 0
        var currentRequest = request

        while (attempt <= maxRetries) {
            val buffer = StringBuilder()
            val collectedEvents = mutableListOf<AgentStreamEvent>()

            innerAgent.chatStream(currentRequest).collect { event ->
                collectedEvents.add(event)
                if (event is AgentStreamEvent.ContentDelta) {
                    buffer.append(event.text)
                }
            }

            val fullResponse = buffer.toString()

            val invariants = currentState.currentInvariants
            val validationResult = if (invariants.isEmpty()) {
                ValidationResult(isValid = true)
            } else {
                withContext(Dispatchers.IO) {
                    validateWithLLM(fullResponse, invariants)
                }
            }

            if (validationResult.isValid) {
                collectedEvents.forEach { emit(it) }

                withContext(Dispatchers.IO) {
                    val signal = parseSignal(fullResponse)
                    val newState = applySignalAndTransition(currentState, signal, fullResponse)
                    taskStateStorage.saveState(newState)
                }
                return@flow
            }

            attempt++
            if (attempt > maxRetries) {
                collectedEvents.forEach { emit(it) }
                emit(AgentStreamEvent.ContentDelta(
                    "\n\n⚠️ Валидация не пройдена после $maxRetries попыток. " +
                    "Нарушения: ${validationResult.violations.joinToString("; ")}"
                ))
                withContext(Dispatchers.IO) {
                    taskStateStorage.saveState(
                        currentState.copy(
                            retryCount = currentState.retryCount + attempt,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
                return@flow
            }

            currentRequest = buildRetryRequest(request, validationResult.violations, attempt)
            currentState = currentState.copy(retryCount = currentState.retryCount + 1)
        }
    }

    // ==================== Private: построение промптов ====================

    /**
     * Встраивает task-блок в system-промпт.
     *
     * Итоговая структура:
     * ```
     * <existingSystemPrompt>
     *
     * ## Completed phases          ← итоги завершённых фаз (из phaseOutputs)
     * ### PLANNING
     * <итог фазы планирования>
     *
     * ## Task State                ← текущее состояние автомата
     * Phase: EXECUTION
     * Current step: ...
     * Expected action: ...
     * Invariants for this phase:
     * - ...
     *
     * ## Instructions              ← теги для LLM
     * ```
     *
     * ### Шаг 3: ограничение тега [PHASE: X]
     * В секции Instructions тег [PHASE: X] теперь показывает только
     * единственную разрешённую следующую фазу (state.phase.next()).
     * Если текущая фаза — DONE, инструкция по смене фазы не добавляется совсем.
     * Это исключает саму возможность того, что LLM предложит прыжок через фазу.
     */
    private fun buildSystemPromptBlock(state: TaskState, existingPrompt: String?): String {
        val sb = StringBuilder()

        if (!existingPrompt.isNullOrBlank()) {
            sb.appendLine(existingPrompt)
            sb.appendLine()
        }

        // Секция итогов завершённых фаз — передаёт контекст между фазами
        if (state.phaseOutputs.isNotEmpty()) {
            sb.appendLine("## Completed phases")
            state.phaseOutputs.forEach { po ->
                sb.appendLine("### ${po.phase.name}")
                sb.appendLine(po.output)
                sb.appendLine()
            }
        }

        sb.appendLine("## Task State")
        sb.appendLine("Phase: ${state.phase.name}")
        if (state.currentStep.isNotBlank()) {
            sb.appendLine("Current step: ${state.currentStep}")
        }
        if (state.expectedAction.isNotBlank()) {
            sb.appendLine("Expected action: ${state.expectedAction}")
        }

        val invariants = state.currentInvariants
        if (invariants.isNotEmpty()) {
            sb.appendLine("Invariants for this phase:")
            invariants.forEach { sb.appendLine("- $it") }
        }

        sb.appendLine()
        sb.appendLine("## Instructions")
        sb.appendLine("At the end of your response, if the current step is complete, add: [PHASE_COMPLETE]")

        // Шаг 3: показываем только единственную разрешённую следующую фазу.
        // LLM не видит другие варианты и не может предложить прыжок через фазу.
        val nextPhase = state.phase.next()
        if (nextPhase != null) {
            sb.appendLine("To suggest moving to the next phase: [PHASE: ${nextPhase.name.lowercase()}]")
        }

        sb.appendLine("To update the current step description: [STEP: description]")
        sb.appendLine("To update the expected action: [EXPECTED: description]")
        sb.appendLine("To summarize the current phase output for the next phase: [OUTPUT: summary text]")
        sb.append("Do NOT explain these tags — just append them silently.")

        return sb.toString().trimEnd()
    }

    private fun buildRetryRequest(
        original: AgentRequest,
        violations: List<String>,
        attempt: Int
    ): AgentRequest {
        val violationBlock = violations.joinToString("\n") { "- $it" }
        val retrySystemPrompt = buildString {
            original.systemPrompt?.let { appendLine(it); appendLine() }
            appendLine("## Retry (attempt $attempt)")
            appendLine("Your previous response violated the following invariants:")
            appendLine(violationBlock)
            appendLine()
            append("Please correct your response to comply with all invariants.")
        }
        return original.copy(systemPrompt = retrySystemPrompt.trimEnd())
    }

    // ==================== Private: парсинг сигнала ====================

    /**
     * Извлекает [TaskSignal] из полного ответа LLM.
     *
     * Парсинг идёт в порядке приоритета:
     * 1. `[PHASE: <name>]`     → [TaskSignal.SuggestPhase]
     * 2. `[PHASE_COMPLETE]`    → [TaskSignal.PhaseComplete]
     * 3. `[STEP: <text>]`      → [TaskSignal.UpdateStep]
     * 4. `[EXPECTED: <text>]`  → [TaskSignal.UpdateExpected]
     * 5. ничего                → [TaskSignal.None]
     *
     * Тег `[OUTPUT: ...]` обрабатывается отдельно в [extractPhaseOutput] —
     * он не является сигналом перехода.
     */
    internal fun parseSignal(response: String): TaskSignal {
        val phaseTagRegex = Regex("""\[PHASE:\s*(\w+)\]""", RegexOption.IGNORE_CASE)
        phaseTagRegex.find(response)?.let { match ->
            val phaseName = match.groupValues[1].uppercase()
            val phase = runCatching { TaskPhase.valueOf(phaseName) }.getOrNull()
            if (phase != null) return TaskSignal.SuggestPhase(phase)
        }

        if (response.contains("[PHASE_COMPLETE]", ignoreCase = true)) {
            return TaskSignal.PhaseComplete
        }

        val stepRegex = Regex("""\[STEP:\s*(.+?)\]""", RegexOption.IGNORE_CASE)
        stepRegex.find(response)?.let { match ->
            val step = match.groupValues[1].trim()
            if (step.isNotEmpty()) return TaskSignal.UpdateStep(step)
        }

        val expectedRegex = Regex("""\[EXPECTED:\s*(.+?)\]""", RegexOption.IGNORE_CASE)
        expectedRegex.find(response)?.let { match ->
            val expected = match.groupValues[1].trim()
            if (expected.isNotEmpty()) return TaskSignal.UpdateExpected(expected)
        }

        return TaskSignal.None
    }

    /**
     * Извлекает итог фазы из тега `[OUTPUT: <текст>]` в ответе LLM.
     *
     * Если тег отсутствует — берёт последние [PHASE_OUTPUT_FALLBACK_LENGTH] символов
     * ответа как fallback. Это гарантирует, что [PhaseOutput] всегда содержит
     * осмысленный контент даже если LLM не добавил тег явно.
     */
    internal fun extractPhaseOutput(response: String): String {
        // setOf(...) — правильный способ передать несколько RegexOption
        val outputRegex = Regex(
            pattern = """\[OUTPUT:\s*(.+?)\]""",
            options = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        outputRegex.find(response)?.let { match ->
            val extracted = match.groupValues[1].trim()
            if (extracted.isNotEmpty()) return extracted
        }
        // Fallback: последние N символов ответа (без тегов автомата)
        val cleaned = response
            .replace(
                regex = Regex(
                    pattern = """\[(PHASE_COMPLETE|PHASE|STEP|EXPECTED|OUTPUT):[^\]]*\]""",
                    option = RegexOption.IGNORE_CASE
                ),
                replacement = ""
            )
            .replace("[PHASE_COMPLETE]", "", ignoreCase = true)
            .trim()
        return cleaned.takeLast(PHASE_OUTPUT_FALLBACK_LENGTH).trim()
    }

    /**
     * Применяет [TaskSignal] к состоянию и возвращает новое состояние.
     *
     * При [TaskSignal.PhaseComplete] — всегда переходит строго на state.phase.next().
     * При [TaskSignal.SuggestPhase] — переход выполняется только если signal.next
     * совпадает с разрешённой следующей фазой ([isTransitionAllowed]).
     * Недопустимый SuggestPhase молча игнорируется: состояние не меняется.
     */
    private suspend fun applySignalAndTransition(
        state: TaskState,
        signal: TaskSignal,
        fullResponse: String
    ): TaskState {
        val now = System.currentTimeMillis()

        return when (signal) {
            is TaskSignal.PhaseComplete -> {
                // PhaseComplete всегда идёт строго на next() — прыжок невозможен
                val next = state.phase.next()
                if (next != null) {
                    transitionToPhase(state, next, fullResponse, now)
                } else {
                    state.copy(retryCount = 0, updatedAt = now)
                }
            }

            is TaskSignal.SuggestPhase -> {
                // Шаг 1+2: проверяем через isTransitionAllowed — только +1 фаза разрешена.
                // Если LLM предложил прыжок (например, PLANNING → DONE) — игнорируем молча.
                if (isTransitionAllowed(state.phase, signal.next)) {
                    transitionToPhase(state, signal.next, fullResponse, now)
                } else {
                    state.copy(retryCount = 0, updatedAt = now)
                }
            }

            is TaskSignal.UpdateStep -> state.copy(
                currentStep = signal.step,
                retryCount = 0,
                updatedAt = now
            )

            is TaskSignal.UpdateExpected -> state.copy(
                expectedAction = signal.expected,
                retryCount = 0,
                updatedAt = now
            )

            is TaskSignal.None -> state.copy(retryCount = 0, updatedAt = now)
        }
    }

    /**
     * Выполняет переход в фазу [next]:
     * 1. Фиксирует [PhaseOutput] завершённой фазы из [fullResponse]
     * 2. Очищает WORKING-память ([LayeredMemoryStrategy]) — данные старой фазы устарели
     * 3. Возвращает новый [TaskState] с обновлённой фазой и сохранённым [PhaseOutput]
     *
     * При переходе в [TaskPhase.DONE] — задача помечается неактивной.
     */
    private suspend fun transitionToPhase(
        state: TaskState,
        next: TaskPhase,
        fullResponse: String,
        now: Long
    ): TaskState {
        // 1. Фиксируем итог завершённой фазы
        val phaseOutput = PhaseOutput(
            phase = state.phase,
            output = extractPhaseOutput(fullResponse),
            completedAt = now
        )
        val updatedOutputs = state.phaseOutputs + phaseOutput

        // 2. Очищаем WORKING-память — данные завершённой фазы больше не актуальны
        clearWorkingMemoryIfNeeded()

        // 3. Строим новое состояние
        return if (next == TaskPhase.DONE) {
            state.copy(
                phase = TaskPhase.DONE,
                currentStep = "Задача завершена",
                expectedAction = "",
                retryCount = 0,
                isActive = false,
                updatedAt = now,
                phaseOutputs = updatedOutputs
            )
        } else {
            state.copy(
                phase = next,
                currentStep = defaultStepForPhase(next),
                expectedAction = defaultExpectedForPhase(next),
                retryCount = 0,
                updatedAt = now,
                phaseOutputs = updatedOutputs
            )
        }
    }

    /**
     * Сбрасывает WORKING-память если [innerAgent] использует [LayeredMemoryStrategy].
     *
     * WORKING-память хранит данные текущей фазы (шаги, промежуточные результаты).
     * При смене фазы эти данные устарели — новая фаза начинает с чистого листа.
     * Итог завершённой фазы уже зафиксирован в [PhaseOutput] и попадёт в system-промпт.
     *
     * Если стратегия не [LayeredMemoryStrategy] — вызов игнорируется.
     */
    private suspend fun clearWorkingMemoryIfNeeded() {
        val layeredStrategy = innerAgent.truncationStrategy as? LayeredMemoryStrategy
        layeredStrategy?.clearWorkingMemory()
    }

    // ==================== Private: контроль переходов ====================

    /**
     * Единственный источник правил допустимости переходов между фазами.
     *
     * Переход из [from] в [target] разрешён тогда и только тогда,
     * когда [target] == [from].next() — то есть строго следующая фаза.
     * Прыжки через фазы (PLANNING → VALIDATION, PLANNING → DONE и т.п.) запрещены.
     *
     * Используется в:
     * - [applySignalAndTransition] — для сигнала [TaskSignal.SuggestPhase]
     *
     * Не используется для [TaskSignal.PhaseComplete] и [advancePhase], так как
     * там переход всегда строится через [TaskPhase.next] напрямую.
     */
    private fun isTransitionAllowed(from: TaskPhase, target: TaskPhase): Boolean =
        from.next() == target

    // ==================== Private: LLM-валидация ====================

    /**
     * Отдельный LLM-вызов: «соблюдены ли инварианты в ответе?»
     *
     * Возвращает [ValidationResult.isValid] = true если все инварианты соблюдены.
     * LLM должен ответить строго в формате:
     * ```
     * VALID
     * ```
     * или
     * ```
     * INVALID
     * - нарушение 1
     * - нарушение 2
     * ```
     */
    private suspend fun validateWithLLM(
        response: String,
        invariants: List<String>
    ): ValidationResult {
        val invariantsList = invariants.joinToString("\n") { "- $it" }
        val validationPrompt = buildString {
            appendLine("You are a validation assistant. Check if the response below complies with ALL invariants.")
            appendLine()
            appendLine("Invariants:")
            appendLine(invariantsList)
            appendLine()
            appendLine("Response to validate:")
            appendLine(response.take(MAX_VALIDATION_RESPONSE_LENGTH))
            appendLine()
            appendLine("Reply with exactly one of:")
            appendLine("VALID")
            appendLine("or")
            appendLine("INVALID")
            appendLine("- <violation 1>")
            append("- <violation 2> (etc.)")
        }

        val request = ChatRequest(
            messages = listOf(ApiMessage(role = "user", content = validationPrompt)),
            model = taskModel,
            temperature = 0f,
            max_tokens = 300L
        )

        val result = StringBuilder()
        api.sendMessageStream(request).collect { chunk ->
            if (chunk is StatsStreamResult.Content) result.append(chunk.text)
        }

        return parseValidationResponse(result.toString().trim())
    }

    private fun parseValidationResponse(response: String): ValidationResult {
        if (response.isBlank()) return ValidationResult(isValid = true)
        val firstLine = response.lines().firstOrNull()?.trim() ?: ""

        return if (firstLine.equals("VALID", ignoreCase = true)) {
            ValidationResult(isValid = true)
        } else {
            val violations = response.lines()
                .drop(1)
                .map { it.trimStart('-', '*', '•', ' ').trim() }
                .filter { it.isNotEmpty() }
            ValidationResult(isValid = false, violations = violations.ifEmpty {
                listOf("Response does not comply with invariants")
            })
        }
    }

    // ==================== Private: проверка готовности к переходу ====================

    /**
     * LLM-оценка готовности к переходу в следующую фазу.
     * Используется в [advancePhase] для ручного перехода.
     */
    private suspend fun checkPhaseReadiness(
        state: TaskState,
        history: List<AgentMessage>
    ): ValidationResult {
        val historyText = history.joinToString("\n") { msg ->
            val role = when (msg.role) {
                Role.USER      -> "User"
                Role.ASSISTANT -> "Assistant"
                Role.SYSTEM    -> "System"
            }
            "$role: ${msg.content}"
        }

        val prompt = buildString {
            appendLine("You are evaluating whether the current phase is ready to transition to the next.")
            appendLine()
            appendLine("Current phase: ${state.phase.name}")
            appendLine("Current step: ${state.currentStep}")
            val next = state.phase.next()
            if (next != null) appendLine("Proposed next phase: ${next.name}")
            appendLine()
            val invariants = state.currentInvariants
            if (invariants.isNotEmpty()) {
                appendLine("Phase invariants:")
                invariants.forEach { appendLine("- $it") }
                appendLine()
            }
            appendLine("Recent conversation (last ${history.size} messages):")
            appendLine(historyText.take(MAX_VALIDATION_RESPONSE_LENGTH))
            appendLine()
            appendLine("Is the current phase sufficiently completed to move to the next? Reply:")
            appendLine("VALID   — yes, ready to advance")
            appendLine("INVALID — no, not ready")
            append("- <reason> (if INVALID)")
        }

        val request = ChatRequest(
            messages = listOf(ApiMessage(role = "user", content = prompt)),
            model = taskModel,
            temperature = 0f,
            max_tokens = 200L
        )

        val result = StringBuilder()
        api.sendMessageStream(request).collect { chunk ->
            if (chunk is StatsStreamResult.Content) result.append(chunk.text)
        }

        return parseValidationResponse(result.toString().trim())
    }

    // ==================== Private: архивирование ====================

    /**
     * LLM-вызов: создаёт краткое summary завершённой задачи для архива.
     */
    private suspend fun archiveTask(
        state: TaskState,
        history: List<AgentMessage>
    ): List<ArchivedTask> {
        val historyText = history.joinToString("\n") { msg ->
            "${if (msg.role == Role.USER) "User" else "Assistant"}: ${msg.content}"
        }.take(MAX_VALIDATION_RESPONSE_LENGTH)

        val prompt = buildString {
            appendLine("Summarize the completed task in 1-2 sentences for archiving.")
            appendLine()
            appendLine("Task phase: ${state.phase.name}")
            appendLine("Last step: ${state.currentStep}")
            appendLine()
            appendLine("Conversation excerpt:")
            append(historyText)
        }

        val request = ChatRequest(
            messages = listOf(ApiMessage(role = "user", content = prompt)),
            model = taskModel,
            temperature = 0.3f,
            max_tokens = 150L
        )

        val summary = StringBuilder()
        runCatching {
            api.sendMessageStream(request).collect { chunk ->
                if (chunk is StatsStreamResult.Content) summary.append(chunk.text)
            }
        }

        val archived = ArchivedTask(
            taskId = state.taskId,
            summary = summary.toString().trim().ifEmpty { "Задача завершена: ${state.currentStep}" },
            completedAt = System.currentTimeMillis()
        )
        return state.archivedTasks + archived
    }

    // ==================== Private: defaults ====================

    private fun defaultStepForPhase(phase: TaskPhase): String = when (phase) {
        TaskPhase.PLANNING   -> "Определение цели и декомпозиция задачи"
        TaskPhase.EXECUTION  -> "Выполнение шагов задачи"
        TaskPhase.VALIDATION -> "Проверка результата"
        TaskPhase.DONE       -> "Задача завершена"
    }

    private fun defaultExpectedForPhase(phase: TaskPhase): String = when (phase) {
        TaskPhase.PLANNING   -> "Опишите задачу, которую нужно решить"
        TaskPhase.EXECUTION  -> "Выполните следующий шаг задачи"
        TaskPhase.VALIDATION -> "Проверьте результат на соответствие цели"
        TaskPhase.DONE       -> ""
    }

    // ==================== Companion ====================

    companion object {
        const val DEFAULT_MAX_RETRIES = 3

        /** Сколько последних сообщений истории передаётся при LLM-валидации готовности */
        private const val VALIDATION_HISTORY_WINDOW = 10

        /** Максимальная длина ответа LLM, передаваемая в контекст валидации */
        private const val MAX_VALIDATION_RESPONSE_LENGTH = 3000

        /**
         * Длина fallback-фрагмента ответа LLM, используемого как [PhaseOutput]
         * если LLM не добавил тег `[OUTPUT: ...]`.
         */
        private const val PHASE_OUTPUT_FALLBACK_LENGTH = 500
    }
}

// ==================== Результат advancePhase ====================

/**
 * Результат попытки ручного перехода к следующей фазе ([TaskStateMachineAgent.advancePhase]).
 */
sealed class AdvancePhaseResult {
    /** Переход выполнен успешно */
    data class Advanced(val newState: TaskState) : AdvancePhaseResult()

    /** LLM решил, что текущая фаза ещё не завершена */
    data class NotReady(val reasons: List<String>) : AdvancePhaseResult()

    /** Нет активной задачи */
    data object NoActiveTask : AdvancePhaseResult()

    /** Задача уже в фазе DONE */
    data object AlreadyDone : AdvancePhaseResult()
}

// ==================== Расширения TaskPhase ====================

/** Следующая фаза по порядку. Для [TaskPhase.DONE] возвращает null. */
fun TaskPhase.next(): TaskPhase? = when (this) {
    TaskPhase.PLANNING   -> TaskPhase.EXECUTION
    TaskPhase.EXECUTION  -> TaskPhase.VALIDATION
    TaskPhase.VALIDATION -> TaskPhase.DONE
    TaskPhase.DONE       -> null
}
