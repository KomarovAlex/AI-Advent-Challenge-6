package ru.koalexse.aichallenge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import ru.koalexse.aichallenge.data.ChatRepository
import ru.koalexse.aichallenge.data.OpenAIApi
import ru.koalexse.aichallenge.ui.ChatIntent
import ru.koalexse.aichallenge.ui.ChatScreen
import ru.koalexse.aichallenge.ui.ChatViewModel
import ru.koalexse.aichallenge.ui.state.ChatUiState
import ru.koalexse.aichallenge.ui.theme.AiChallengeTheme

class MainActivity : ComponentActivity() {
    // Ручной DI - создаем зависимости здесь
    private val api by lazy {
        OpenAIApi(
            apiKey = BuildConfig.OPENAI_API_KEY,
            url = BuildConfig.OPENAI_URL,
        )
    }
    private val repository by lazy { ChatRepository(api, model = BuildConfig.OPENAI_MODEL) }
    private val viewModel by lazy { ChatViewModel(repository) }

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
    AiChallengeTheme {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar({ Text(BuildConfig.OPENAI_MODEL) }, actions = {
                    IconButton(onClick = { handleIntent(ChatIntent.OpenSettings) }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Настройки"
                        )
                    }
                    IconButton(onClick = { handleIntent(ChatIntent.ClearSession) }) {
                        Icon(
                            imageVector = Icons.Default.CleaningServices,
                            contentDescription = "Очистить"
                        )
                    }
                })
            }
        ) { paddingValues ->
            ChatScreen(
                modifier = Modifier.padding(paddingValues),
                uiState = state,
                onIntent = handleIntent
            )
        }
    }
}

@[Composable Preview]
fun ContentPreview() {
    Content(remember { mutableStateOf(ChatUiState()) }) { }
}
