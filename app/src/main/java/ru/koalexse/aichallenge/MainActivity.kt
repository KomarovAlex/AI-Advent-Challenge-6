package ru.koalexse.aichallenge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import ru.koalexse.aichallenge.data.ChatRepository
import ru.koalexse.aichallenge.data.OpenAIApi
import ru.koalexse.aichallenge.ui.ChatScreen
import ru.koalexse.aichallenge.ui.ChatViewModel
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
            AiChallengeTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ChatScreen(viewModel.state.collectAsState()) {
                        viewModel.handleIntent(it)
                    }
                }
            }
        }
    }
}
