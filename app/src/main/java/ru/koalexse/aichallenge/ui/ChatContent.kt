package ru.koalexse.aichallenge.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import ru.koalexse.aichallenge.R
import ru.koalexse.aichallenge.agent.task.TaskPhase
import ru.koalexse.aichallenge.ui.mcp.McpToolsDialog
import ru.koalexse.aichallenge.ui.mcp.McpViewModel
import ru.koalexse.aichallenge.ui.state.ChatUiState
import ru.koalexse.aichallenge.ui.state.ContextStrategyType
import ru.koalexse.aichallenge.ui.state.SettingsData
import ru.koalexse.aichallenge.ui.state.displayName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatContent(
    state: State<ChatUiState>,
    handleIntent: (ChatIntent) -> Unit,
    onNavigateToProfiles: () -> Unit,
    mcpViewModel: McpViewModel? = null
) {
    val uiState by remember { derivedStateOf { state.value } }
    var overflowExpanded by remember { mutableStateOf(false) }
    var isMcpDialogOpen by remember { mutableStateOf(false) }

    val isPlanningMode = uiState.isPlanningMode
    val taskState = uiState.taskState
    val hasActiveTask = taskState?.isActive == true
    val taskPhase = taskState?.phase

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    if (isPlanningMode && hasActiveTask && taskPhase != null) {
                        Text("${taskPhase.displayName()} · ${uiState.settingsData.model}")
                    } else {
                        Text(uiState.settingsData.model)
                    }
                },
                actions = {
                    // ── Planning mode: кнопка «▶ Новая задача» ──────────────
                    if (isPlanningMode && (!hasActiveTask || taskPhase == TaskPhase.DONE)) {
                        IconButton(
                            onClick = { handleIntent(ChatIntent.OpenStartTaskDialog) },
                            enabled = !uiState.isLoading
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Start new task"
                            )
                        }
                    }

                    // ── Strategy-specific primary actions ────────────────────
                    when (uiState.activeStrategy) {

                        ContextStrategyType.STICKY_FACTS -> {
                            IconButton(
                                onClick = { handleIntent(ChatIntent.RefreshFacts) },
                                enabled = !uiState.isLoading && !uiState.isRefreshingFacts
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = stringResource(R.string.toolbar_refresh_facts)
                                )
                            }
                            IconButton(
                                onClick = onNavigateToProfiles,
                                enabled = !uiState.isLoading
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = stringResource(R.string.toolbar_profiles)
                                )
                            }
                        }

                        ContextStrategyType.BRANCHING -> {
                            IconButton(
                                onClick = { handleIntent(ChatIntent.CreateCheckpoint) },
                                enabled = !uiState.isLoading &&
                                        !uiState.isSwitchingBranch &&
                                        !uiState.isBranchLimitReached
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bookmark,
                                    contentDescription = stringResource(R.string.toolbar_checkpoint)
                                )
                            }
                            IconButton(
                                onClick = { handleIntent(ChatIntent.OpenBranchDialog) },
                                enabled = !uiState.isLoading && !uiState.isSwitchingBranch
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AccountTree,
                                    contentDescription = stringResource(R.string.toolbar_branches)
                                )
                            }
                        }

                        ContextStrategyType.LAYERED_MEMORY -> {
                            IconButton(
                                onClick = { handleIntent(ChatIntent.RefreshWorkingMemory) },
                                enabled = !uiState.isLoading && !uiState.isRefreshingWorkingMemory
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Work,
                                    contentDescription = stringResource(R.string.toolbar_refresh_working_memory)
                                )
                            }
                            IconButton(
                                onClick = { handleIntent(ChatIntent.RefreshLongTermMemory) },
                                enabled = !uiState.isLoading && !uiState.isRefreshingLongTermMemory
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Psychology,
                                    contentDescription = stringResource(R.string.toolbar_refresh_long_term_memory)
                                )
                            }
                        }

                        else -> {
                            IconButton(
                                onClick = onNavigateToProfiles,
                                enabled = !uiState.isLoading
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = stringResource(R.string.toolbar_profiles)
                                )
                            }
                            IconButton(
                                onClick = { handleIntent(ChatIntent.OpenSettings) },
                                enabled = !uiState.isLoading
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = stringResource(R.string.toolbar_settings)
                                )
                            }
                        }
                    }

                    // ── Overflow menu (⋮) ────────────────────────────────────
                    IconButton(onClick = { overflowExpanded = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.toolbar_more_actions)
                        )
                    }

                    DropdownMenu(
                        expanded = overflowExpanded,
                        onDismissRequest = { overflowExpanded = false }
                    ) {
                        when (uiState.activeStrategy) {
                            ContextStrategyType.BRANCHING,
                            ContextStrategyType.LAYERED_MEMORY -> {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.toolbar_profiles)) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Person, contentDescription = null)
                                    },
                                    onClick = {
                                        overflowExpanded = false
                                        onNavigateToProfiles()
                                    },
                                    enabled = !uiState.isLoading
                                )
                            }
                            else -> {}
                        }

                        if (uiState.activeStrategy != ContextStrategyType.SLIDING_WINDOW &&
                            uiState.activeStrategy != ContextStrategyType.SUMMARY
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.toolbar_settings)) },
                                leadingIcon = {
                                    Icon(Icons.Default.Settings, contentDescription = null)
                                },
                                onClick = {
                                    overflowExpanded = false
                                    handleIntent(ChatIntent.OpenSettings)
                                },
                                enabled = !uiState.isLoading
                            )
                        }

                        // Clear session — always in overflow
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.toolbar_clear)) },
                            leadingIcon = {
                                Icon(Icons.Default.CleaningServices, contentDescription = null)
                            },
                            onClick = {
                                overflowExpanded = false
                                handleIntent(ChatIntent.ClearSession)
                            },
                            enabled = !uiState.isLoading
                        )

                        // MCP Tools — показываем только если mcpViewModel передан
                        if (mcpViewModel != null) {
                            DropdownMenuItem(
                                text = { Text("MCP Tools") },
                                leadingIcon = {
                                    Icon(Icons.Default.Build, contentDescription = null)
                                },
                                onClick = {
                                    overflowExpanded = false
                                    isMcpDialogOpen = true
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        ChatScreen(
            modifier = Modifier.padding(paddingValues),
            uiState = state,
            onIntent = handleIntent
        )
    }

    // ── Branch dialog ──────────────────────────────────────────────────────
    if (uiState.isBranchDialogOpen) {
        BranchSwitchDialog(
            branches = uiState.branches,
            activeBranchId = uiState.activeBranchId,
            onDismiss = { handleIntent(ChatIntent.OpenBranchDialog) },
            onSwitch = { handleIntent(ChatIntent.SwitchBranch(it)) }
        )
    }

    // ── Start Task dialog (Planning mode) ──────────────────────────────────
    if (uiState.isStartTaskDialogOpen) {
        StartTaskDialog(
            onDismiss = { handleIntent(ChatIntent.CloseStartTaskDialog) },
            onConfirm = { phaseInvariants ->
                handleIntent(ChatIntent.StartTask(phaseInvariants))
            }
        )
    }

    // ── Task validation / advance error dialog ─────────────────────────────
    val taskError = uiState.taskValidationError
    if (taskError != null) {
        AlertDialog(
            onDismissRequest = { handleIntent(ChatIntent.ClearTaskError) },
            title = { Text("⚠️ Phase advance blocked") },
            text = { Text(taskError) },
            confirmButton = {
                TextButton(onClick = { handleIntent(ChatIntent.ClearTaskError) }) {
                    Text("OK")
                }
            }
        )
    }

    // ── MCP Tools dialog ───────────────────────────────────────────────────
    if (isMcpDialogOpen && mcpViewModel != null) {
        McpToolsDialog(
            viewModel = mcpViewModel,
            onDismiss = { isMcpDialogOpen = false }
        )
    }
}

@[Composable Preview]
fun ContentPreview() {
    ChatContent(
        state = remember {
            mutableStateOf(ChatUiState(settingsData = SettingsData("deepseek-v3.2")))
        },
        handleIntent = {},
        onNavigateToProfiles = {}
    )
}

@[Composable Preview]
fun ContentPlanningModePreview() {
    ChatContent(
        state = remember {
            mutableStateOf(
                ChatUiState(
                    settingsData = SettingsData("deepseek-v3.2", isPlanningMode = true),
                    isPlanningMode = true
                )
            )
        },
        handleIntent = {},
        onNavigateToProfiles = {}
    )
}
