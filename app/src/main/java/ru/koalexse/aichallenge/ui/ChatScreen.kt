package ru.koalexse.aichallenge.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import ru.koalexse.aichallenge.R
import ru.koalexse.aichallenge.domain.Message
import ru.koalexse.aichallenge.domain.SessionTokenStats
import ru.koalexse.aichallenge.domain.TokenStats
import ru.koalexse.aichallenge.ui.state.ChatUiState
import ru.koalexse.aichallenge.ui.state.SettingsData

@OptIn(FlowPreview::class)
@Composable
fun ChatScreen(
    modifier: Modifier,
    uiState: State<ChatUiState>,
    onIntent: (ChatIntent) -> Unit,
) {
    val listState = rememberLazyListState()
    val currentOnIntent by rememberUpdatedState(onIntent)
    val currentUiState by rememberUpdatedState(uiState.value)

    // Оптимизированное получение значений
    val messages by remember { derivedStateOf { currentUiState.messages } }
    val isLoading by remember { derivedStateOf { currentUiState.isLoading } }
    val currentInput by remember { derivedStateOf { currentUiState.currentInput } }
    val error by remember { derivedStateOf { currentUiState.error } }
    val sessionStats by remember { derivedStateOf { currentUiState.sessionStats } }
    val compressedMessageCount by remember { derivedStateOf { currentUiState.compressedMessageCount } }

    // Автоскролл при добавлении новых сообщений
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    // Автоскролл при стриминге (обновление текста последнего сообщения)
    LaunchedEffect(Unit) {
        snapshotFlow {
            val lastMessage = uiState.value.messages.lastOrNull()
            lastMessage?.text?.length to lastMessage?.isLoading
        }
            .distinctUntilChanged()
            .debounce(50)
            .collect { (_, isMessageLoading) ->
                val size = uiState.value.messages.size
                if (isMessageLoading == true && size > 0) {
                    listState.scrollToItem(0)
                }
            }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // Список сообщений
        MessageList(
            messages = messages,
            listState = listState,
            modifier = Modifier.weight(1f)
        )

        // Индикатор загрузки
        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Footer со статистикой сессии
        SessionStatsFooter(
            sessionStats = sessionStats,
            compressedMessageCount = compressedMessageCount
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Поле ввода сообщения
        InputRow(
            currentInput = currentInput,
            isLoading = isLoading,
            onInputChange = remember { { text: String -> currentOnIntent(ChatIntent.UpdateInput(text)) } },
            onSendClick = remember {
                {
                    currentOnIntent(ChatIntent.SendMessage(uiState.value.currentInput))
                }
            }
        )
        Spacer(modifier = Modifier.height(16.dp))
    }

    // Ошибка
    if (error != null) {
        ErrorDialog(
            error = error!!,
            onDismiss = remember { { currentOnIntent(ChatIntent.ClearError) } }
        )
    }

    if (currentUiState.isSettingsOpen) {
        MultiFieldInputDialog(
            settings = currentUiState.settingsData,
            availableModels = currentUiState.availableModels,
            onDismiss = { onIntent(ChatIntent.OpenSettings) }
        ) {
            currentOnIntent(ChatIntent.SaveSettings(it))
        }
    }
}

@Composable
private fun SessionStatsFooter(
    sessionStats: SessionTokenStats?,
    compressedMessageCount: Int,
    modifier: Modifier = Modifier
) {
    if (sessionStats != null || compressedMessageCount > 0) {
        Column(modifier = modifier.fillMaxWidth()) {
            HorizontalDivider(
                modifier = Modifier.padding(bottom = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            
            // Статистика сессии
            if (sessionStats != null) {
                Text(
                    text = stringResource(
                        R.string.session_stats_format,
                        sessionStats.totalPromptTokens,
                        sessionStats.totalCompletionTokens,
                        sessionStats.totalTokens,
                        sessionStats.messageCount
                    ),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            
            // Статистика компрессии
            if (compressedMessageCount > 0) {
                Text(
                    text = stringResource(
                        R.string.compressed_stats_format,
                        compressedMessageCount
                    ),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun MessageList(
    messages: List<Message>,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    val messageList = remember(messages) { messages.reversed() }
    LazyColumn(
        modifier = modifier,
        state = listState,
        verticalArrangement = Arrangement.Bottom,
        reverseLayout = true
    ) {
        items(messageList, key = { it.id }) { message ->
            MessageBubble(
                isUser = message.isUser,
                text = message.text,
                isLoading = message.isLoading,
                tokenStats = message.tokenStats,
                responseDurationMs = message.responseDurationMs
            )
        }
    }
}

@Composable
private fun InputRow(
    currentInput: String,
    isLoading: Boolean,
    onInputChange: (String) -> Unit,
    onSendClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = currentInput,
            onValueChange = onInputChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.message_placeholder)) },
            enabled = !isLoading,
            maxLines = 3
        )

        Spacer(modifier = Modifier.width(8.dp))

        Button(
            onClick = onSendClick,
            enabled = currentInput.isNotBlank() && !isLoading
        ) {
            Text(stringResource(R.string.send_button))
        }
    }
}

@Composable
private fun ErrorDialog(
    error: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.error_dialog_title)) },
        text = { Text(error) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.error_dialog_ok))
            }
        }
    )
}

@Composable
private fun MessageBubble(
    isUser: Boolean,
    text: String,
    isLoading: Boolean,
    tokenStats: TokenStats? = null,
    responseDurationMs: Long? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .padding(4.dp),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            val clipboardManager = LocalClipboardManager.current
            Column(
                modifier = Modifier
                    .padding(12.dp)
                    .combinedClickable(
                        onClick = {
                            // Можно добавить дополнительное действие при нажатии
                        },
                        onLongClick = {
                            if (!isLoading) {
                                clipboardManager.setText(AnnotatedString(text))
                            }
                        }
                    )
            ) {
                Text(
                    text = text,
                    fontSize = 16.sp
                )

                // Отображение статистики токенов и времени для сообщений ассистента
                if (!isUser && tokenStats != null && !isLoading) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val durationSeconds = (responseDurationMs ?: 0L) / 1000f
                    val ttft = tokenStats.timeToFirstTokenMs ?: 0L
                    Text(
                        text = stringResource(
                            R.string.token_stats_format,
                            tokenStats.promptTokens,
                            tokenStats.completionTokens,
                            tokenStats.totalTokens,
                            ttft,
                            durationSeconds
                        ),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@[Composable Preview]
fun ChatScreenPreview() {
    ChatScreen(modifier = Modifier, remember {
        mutableStateOf(
            ChatUiState(
                messages = listOf(
                    Message(
                        "1",
                        true,
                        "Hello, how are you?"
                    ),
                    Message(
                        "2",
                        false,
                        "I'm doing great, thank you for asking!",
                        tokenStats = TokenStats(100, 50, 150, timeToFirstTokenMs = 350),
                        responseDurationMs = 2500L
                    )
                ),
                settingsData = SettingsData("deepseek-v3.2"),
                sessionStats = SessionTokenStats(
                    totalPromptTokens = 250,
                    totalCompletionTokens = 120,
                    totalTokens = 370,
                    messageCount = 1
                ),
                compressedMessageCount = 20
            )
        )
    }) { }
}
