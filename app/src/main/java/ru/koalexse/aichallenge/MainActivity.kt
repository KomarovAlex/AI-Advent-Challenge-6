package ru.koalexse.aichallenge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import ru.koalexse.aichallenge.di.AppContainer
import ru.koalexse.aichallenge.ui.AgentChatViewModel
import ru.koalexse.aichallenge.ui.ChatContent
import ru.koalexse.aichallenge.ui.ChatIntent
import ru.koalexse.aichallenge.ui.navigation.ChatRoute
import ru.koalexse.aichallenge.ui.navigation.ProfileEditRoute
import ru.koalexse.aichallenge.ui.navigation.ProfileListRoute
import ru.koalexse.aichallenge.ui.profile.ProfileEditScreen
import ru.koalexse.aichallenge.ui.profile.ProfileListIntent
import ru.koalexse.aichallenge.ui.profile.ProfileListScreen
import ru.koalexse.aichallenge.ui.state.ChatUiState
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
