package ru.koalexse.aichallenge.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import ru.koalexse.aichallenge.ui.state.ChatUiState
import ru.koalexse.aichallenge.ui.state.ContextStrategyType
import ru.koalexse.aichallenge.ui.state.SettingsData

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatContent(
    state: State<ChatUiState>,
    handleIntent: (ChatIntent) -> Unit,
    onNavigateToProfiles: () -> Unit
) {
    val uiState by remember { derivedStateOf { state.value } }
    var overflowExpanded by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(uiState.settingsData.model) },
                actions = {
                    // ── Strategy-specific primary actions (≤ 2 icon buttons) ──────────

                    when (uiState.activeStrategy) {

                        ContextStrategyType.STICKY_FACTS -> {
                            // 1 strategy button → show it + Profiles as the 2nd icon
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
                            // 2 strategy buttons fill both slots
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
                            // 2 strategy buttons fill both slots
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
                            // No strategy-specific buttons → Profiles + Settings as the 2 icons
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

                    // ── Overflow menu (⋮) ─────────────────────────────────────────────
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
                        // Strategy-specific items that didn't get their own icon button
                        when (uiState.activeStrategy) {

                            ContextStrategyType.STICKY_FACTS -> {
                                // Profiles is already an icon; Settings + Clear go here
                            }

                            ContextStrategyType.BRANCHING,
                            ContextStrategyType.LAYERED_MEMORY -> {
                                // Both strategy slots taken; Profiles goes in the overflow
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

                            else -> {
                                // Profiles + Settings already shown as icons; nothing extra
                            }
                        }

                        // ── Persistent actions always in overflow ─────────────────────

                        // Settings — only when it wasn't shown as an icon
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

    // ── Branch dialog ─────────────────────────────────────────────────────
    if (uiState.isBranchDialogOpen) {
        BranchSwitchDialog(
            branches = uiState.branches,
            activeBranchId = uiState.activeBranchId,
            onDismiss = { handleIntent(ChatIntent.OpenBranchDialog) },
            onSwitch = { handleIntent(ChatIntent.SwitchBranch(it)) }
        )
    }
}

@[Composable Preview]
fun ContentPreview() {
    ChatContent(
        state = remember { mutableStateOf(ChatUiState(settingsData = SettingsData("deepseek-v3.2"))) },
        handleIntent = {},
        onNavigateToProfiles = {}
    )
}
