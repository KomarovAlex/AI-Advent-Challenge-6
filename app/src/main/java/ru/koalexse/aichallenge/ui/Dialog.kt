package ru.koalexse.aichallenge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.koalexse.aichallenge.R
import ru.koalexse.aichallenge.agent.context.branch.DialogBranch
import ru.koalexse.aichallenge.agent.task.PhaseInvariants
import ru.koalexse.aichallenge.agent.task.TaskPhase
import ru.koalexse.aichallenge.ui.state.ContextStrategyType
import ru.koalexse.aichallenge.ui.state.SettingsData
import ru.koalexse.aichallenge.ui.state.displayName
import ru.koalexse.aichallenge.ui.state.isEmpty

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiFieldInputDialog(
    settings: SettingsData = SettingsData(model = "claude-sonnet-4"),
    availableModels: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onConfirm: (SettingsData) -> Unit
) {
    var settingsData by remember { mutableStateOf(settings) }
    var modelDropdownExpanded by remember { mutableStateOf(false) }
    var strategyDropdownExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

                // ── Выбор модели ──────────────────────────────────────────────
                ExposedDropdownMenuBox(
                    expanded = modelDropdownExpanded,
                    onExpandedChange = { modelDropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = settingsData.model,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.settings_model_label)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelDropdownExpanded)
                        },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = modelDropdownExpanded,
                        onDismissRequest = { modelDropdownExpanded = false }
                    ) {
                        availableModels.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model) },
                                onClick = {
                                    settingsData = settingsData.copy(model = model)
                                    modelDropdownExpanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                        }
                    }
                }

                // ── Выбор стратегии ───────────────────────────────────────────
                ExposedDropdownMenuBox(
                    expanded = strategyDropdownExpanded,
                    onExpandedChange = { strategyDropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = settingsData.strategy.displayName(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.settings_strategy_label)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = strategyDropdownExpanded)
                        },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = strategyDropdownExpanded,
                        onDismissRequest = { strategyDropdownExpanded = false }
                    ) {
                        ContextStrategyType.entries.forEach { strategy ->
                            DropdownMenuItem(
                                text = { Text(strategy.displayName()) },
                                onClick = {
                                    settingsData = settingsData.copy(strategy = strategy)
                                    strategyDropdownExpanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                        }
                    }
                }

                // ── Температура ───────────────────────────────────────────────
                OutlinedTextField(
                    value = settingsData.temperature ?: "",
                    onValueChange = { settingsData = settingsData.copy(temperature = it) },
                    label = { Text(stringResource(R.string.settings_temperature_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // ── Макс. токены ──────────────────────────────────────────────
                OutlinedTextField(
                    value = settingsData.tokens ?: "",
                    onValueChange = { settingsData = settingsData.copy(tokens = it) },
                    label = { Text(stringResource(R.string.settings_max_tokens_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // ── Max Retries (только для TASK_STATE_MACHINE) ───────────────
                if (settingsData.strategy == ContextStrategyType.TASK_STATE_MACHINE) {
                    OutlinedTextField(
                        value = settingsData.maxRetries ?: "",
                        onValueChange = { settingsData = settingsData.copy(maxRetries = it) },
                        label = { Text("Max retries (validation)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(settingsData) },
                enabled = !settingsData.isEmpty()
            ) {
                Text(stringResource(R.string.settings_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel))
            }
        }
    )
}

/**
 * Диалог переключения веток диалога.
 *
 * @param branches      список доступных веток
 * @param activeBranchId id текущей активной ветки
 * @param onDismiss     закрытие без действия
 * @param onSwitch      пользователь выбрал ветку для переключения
 */
@Composable
fun BranchSwitchDialog(
    branches: List<DialogBranch>,
    activeBranchId: String?,
    onDismiss: () -> Unit,
    onSwitch: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.branch_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (branches.isEmpty()) {
                    Text(stringResource(R.string.branch_dialog_empty))
                } else {
                    branches.forEach { branch ->
                        val isActive = branch.id == activeBranchId
                        val label = if (isActive) {
                            "▶ ${branch.name}"
                        } else {
                            branch.name
                        }
                        TextButton(
                            onClick = { if (!isActive) onSwitch(branch.id) },
                            enabled = !isActive,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(label)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel))
            }
        }
    )
}

/**
 * Диалог запуска новой задачи (Task State Machine).
 *
 * Пользователь вводит инварианты для каждой фазы в отдельные поля.
 * Формат ввода: одно правило на строку. Пустые поля — нет инвариантов для фазы.
 *
 * @param onDismiss  закрытие без действия
 * @param onConfirm  пользователь подтвердил запуск задачи с инвариантами
 */
@Composable
fun StartTaskDialog(
    onDismiss: () -> Unit,
    onConfirm: (List<PhaseInvariants>) -> Unit
) {
    var planningInvariants by remember { mutableStateOf("") }
    var executionInvariants by remember { mutableStateOf("") }
    var validationInvariants by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🚀 Start New Task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter invariants for each phase (one rule per line, or leave empty):")

                OutlinedTextField(
                    value = planningInvariants,
                    onValueChange = { planningInvariants = it },
                    label = { Text("🗺️ Planning invariants") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = executionInvariants,
                    onValueChange = { executionInvariants = it },
                    label = { Text("⚙️ Execution invariants") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = validationInvariants,
                    onValueChange = { validationInvariants = it },
                    label = { Text("✅ Validation invariants") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val invariants = buildPhaseInvariants(
                        planningInvariants,
                        executionInvariants,
                        validationInvariants
                    )
                    onConfirm(invariants)
                }
            ) {
                Text("Start Task")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel))
            }
        }
    )
}

// ==================== Helpers ====================

/**
 * Парсит строки инвариантов из UI в список [PhaseInvariants].
 * Пустые строки и фазы без правил игнорируются.
 */
private fun buildPhaseInvariants(
    planning: String,
    execution: String,
    validation: String
): List<PhaseInvariants> {
    val result = mutableListOf<PhaseInvariants>()

    fun parseRules(text: String): List<String> =
        text.lines()
            .map { it.trimStart('-', '*', '•', ' ').trim() }
            .filter { it.isNotEmpty() }

    val planningRules = parseRules(planning)
    if (planningRules.isNotEmpty()) result.add(PhaseInvariants(TaskPhase.PLANNING, planningRules))

    val executionRules = parseRules(execution)
    if (executionRules.isNotEmpty()) result.add(PhaseInvariants(TaskPhase.EXECUTION, executionRules))

    val validationRules = parseRules(validation)
    if (validationRules.isNotEmpty()) result.add(PhaseInvariants(TaskPhase.VALIDATION, validationRules))

    return result
}

fun ContextStrategyType.displayName(): String = when (this) {
    ContextStrategyType.SLIDING_WINDOW      -> "Sliding Window"
    ContextStrategyType.STICKY_FACTS        -> "Sticky Facts"
    ContextStrategyType.BRANCHING           -> "Branching"
    ContextStrategyType.SUMMARY             -> "Summary (LLM)"
    ContextStrategyType.LAYERED_MEMORY      -> "Layered Memory 🧠"
    ContextStrategyType.TASK_STATE_MACHINE  -> "Task State Machine 🤖"
}

// ==================== Previews ====================

@[Composable Preview]
fun MultiFieldInputDialogPreview() {
    MultiFieldInputDialog(
        availableModels = listOf("deepseek-v3.2"),
        onDismiss = {}
    ) {}
}

@[Composable Preview]
fun BranchSwitchDialogPreview() {
    BranchSwitchDialog(
        branches = listOf(
            DialogBranch("1", "Branch 1", emptyList()),
            DialogBranch("2", "Branch 2", emptyList()),
        ),
        activeBranchId = "1",
        onDismiss = {},
        onSwitch = {}
    )
}

@[Composable Preview]
fun StartTaskDialogPreview() {
    StartTaskDialog(
        onDismiss = {},
        onConfirm = {}
    )
}
