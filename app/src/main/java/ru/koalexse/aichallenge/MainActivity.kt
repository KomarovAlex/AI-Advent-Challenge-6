package ru.koalexse.aichallenge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import ru.koalexse.aichallenge.di.AppContainer
import ru.koalexse.aichallenge.ui.AgentChatViewModel
import ru.koalexse.aichallenge.ui.BranchSwitchDialog
import ru.koalexse.aichallenge.ui.ChatIntent
import ru.koalexse.aichallenge.ui.ChatScreen
import ru.koalexse.aichallenge.ui.state.ChatUiState
import ru.koalexse.aichallenge.ui.state.ContextStrategyType
import ru.koalexse.aichallenge.ui.state.SettingsData
import ru.koalexse.aichallenge.ui.theme.AiChallengeTheme

class MainActivity : ComponentActivity() {

    private val appModule by lazy {
        AppContainer.initialize(
            context = applicationContext,
            apiKey = BuildConfig.OPENAI_API_KEY,
            baseUrl = BuildConfig.OPENAI_URL,
            availableModels = BuildConfig.OPENAI_MODELS.split(",")
        )
    }

    private val viewModel: AgentChatViewModel by lazy {
        appModule.createAgentChatViewModelWithCompression()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Content(viewModel.state.collectAsState(), viewModel::handleIntent)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Content(state: State<ChatUiState>, handleIntent: (ChatIntent) -> Unit) {
    val uiState by remember { derivedStateOf { state.value } }

    AiChallengeTheme {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Text(uiState.settingsData.model) },
                    actions = {
                        // ── Кнопка «Обновить факты» (только для StickyFacts) ──────────────
                        if (uiState.activeStrategy == ContextStrategyType.STICKY_FACTS) {
                            IconButton(
                                onClick = { handleIntent(ChatIntent.RefreshFacts) },
                                enabled = !uiState.isLoading && !uiState.isRefreshingFacts
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = stringResource(R.string.toolbar_refresh_facts)
                                )
                            }
                        }

                        // ── Кнопка «Checkpoint» (только для Branching) ─────────────────────
                        if (uiState.activeStrategy == ContextStrategyType.BRANCHING) {
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

                            // ── Кнопка переключения веток ─────────────────────────────────
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

                        // ── Настройки ─────────────────────────────────────────────────────
                        IconButton(
                            onClick = { handleIntent(ChatIntent.OpenSettings) },
                            enabled = !uiState.isLoading
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = stringResource(R.string.toolbar_settings)
                            )
                        }

                        // ── Очистить ─────────────────────────────────────────────────────
                        IconButton(
                            onClick = { handleIntent(ChatIntent.ClearSession) },
                            enabled = !uiState.isLoading
                        ) {
                            Icon(
                                imageVector = Icons.Default.CleaningServices,
                                contentDescription = stringResource(R.string.toolbar_clear)
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

        // ── Диалог веток ─────────────────────────────────────────────────────
        if (uiState.isBranchDialogOpen) {
            BranchSwitchDialog(
                branches = uiState.branches,
                activeBranchId = uiState.activeBranchId,
                onDismiss = { handleIntent(ChatIntent.OpenBranchDialog) },
                onSwitch = { handleIntent(ChatIntent.SwitchBranch(it)) }
            )
        }
    }
}

@[Composable Preview]
fun ContentPreview() {
    Content(remember { mutableStateOf(ChatUiState(settingsData = SettingsData("deepseek-v3.2"))) }) { }
}
