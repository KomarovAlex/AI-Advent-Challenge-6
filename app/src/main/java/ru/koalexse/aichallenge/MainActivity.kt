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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import ru.koalexse.aichallenge.di.AppContainer
import ru.koalexse.aichallenge.ui.AgentChatViewModel
import ru.koalexse.aichallenge.ui.BranchSwitchDialog
import ru.koalexse.aichallenge.ui.ChatIntent
import ru.koalexse.aichallenge.ui.ChatScreen
import ru.koalexse.aichallenge.ui.navigation.ChatRoute
import ru.koalexse.aichallenge.ui.navigation.ProfileEditRoute
import ru.koalexse.aichallenge.ui.navigation.ProfileListRoute
import ru.koalexse.aichallenge.ui.profile.ProfileEditScreen
import ru.koalexse.aichallenge.ui.profile.ProfileListIntent
import ru.koalexse.aichallenge.ui.profile.ProfileListScreen
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

    private val chatViewModel: AgentChatViewModel by lazy {
        appModule.createAgentChatViewModelWithCompression()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppNavigation(
                chatState = chatViewModel.state.collectAsState(),
                handleChatIntent = chatViewModel::handleIntent,
                appModule = appModule
            )
        }
    }
}

@Composable
fun AppNavigation(
    chatState: State<ChatUiState>,
    handleChatIntent: (ChatIntent) -> Unit,
    appModule: ru.koalexse.aichallenge.di.AppModule
) {
    val backStack = rememberNavBackStack(ChatRoute)

    // ViewModels для профилей создаём один раз — они переживают смену ключей в backStack
    val profileListViewModel = remember { appModule.createProfileListViewModel() }
    val profileEditViewModel = remember { appModule.createProfileEditViewModel() }

    val entryProvider = entryProvider<NavKey> {

        entry<ChatRoute> {
            ChatContent(
                state = chatState,
                handleIntent = handleChatIntent,
                onNavigateToProfiles = { backStack.add(ProfileListRoute) }
            )
        }

        entry<ProfileListRoute> {
            LaunchedEffect(Unit) {
                profileListViewModel.handleIntent(ProfileListIntent.Load)
            }
            ProfileListScreen(
                viewModel = profileListViewModel,
                onBack = { backStack.removeLastOrNull() },
                onNavigateToEdit = { profileId ->
                    backStack.add(ProfileEditRoute(profileId))
                }
            )
        }

        entry<ProfileEditRoute> { key ->
            ProfileEditScreen(
                profileId = key.profileId,
                viewModel = profileEditViewModel,
                onBack = { backStack.removeLastOrNull() }
            )
        }
    }

    AiChallengeTheme {
        NavDisplay(
            backStack = backStack,
            entryProvider = entryProvider,
            onBack = { backStack.removeLastOrNull() }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatContent(
    state: State<ChatUiState>,
    handleIntent: (ChatIntent) -> Unit,
    onNavigateToProfiles: () -> Unit
) {
    val uiState by remember { derivedStateOf { state.value } }

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

                    // ── Кнопки Branching ──────────────────────────────────────────────
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

                    // ── Кнопки Layered Memory ─────────────────────────────────────────
                    if (uiState.activeStrategy == ContextStrategyType.LAYERED_MEMORY) {
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

                    // ── Профили ───────────────────────────────────────────────────────
                    IconButton(
                        onClick = onNavigateToProfiles,
                        enabled = !uiState.isLoading
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = stringResource(R.string.toolbar_profiles)
                        )
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

@[Composable Preview]
fun ContentPreview() {
    ChatContent(
        state = remember { mutableStateOf(ChatUiState(settingsData = SettingsData("deepseek-v3.2"))) },
        handleIntent = {},
        onNavigateToProfiles = {}
    )
}
