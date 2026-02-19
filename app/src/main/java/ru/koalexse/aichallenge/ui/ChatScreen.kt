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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import ru.koalexse.aichallenge.domain.Message
import ru.koalexse.aichallenge.ui.state.ChatUiState

@OptIn(FlowPreview::class)
@Composable
fun ChatScreen(
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
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Список сообщений
        MessageList(
            messages = messages,
            listState = listState,
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.height(16.dp))
        // Индикатор загрузки
        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth()
            )
        }

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
    }

    // Ошибка
    if (error != null) {
        ErrorDialog(
            error = error!!,
            onDismiss = remember { { currentOnIntent(ChatIntent.ClearError) } }
        )
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
                isLoading = message.isLoading
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
            placeholder = { Text("Type a message...") },
            enabled = !isLoading,
            maxLines = 3
        )

        Spacer(modifier = Modifier.width(8.dp))

        Button(
            onClick = onSendClick,
            enabled = currentInput.isNotBlank() && !isLoading
        ) {
            Text("Send")
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
        title = { Text("Error") },
        text = { Text(error) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}

@Composable
private fun MessageBubble(
    isUser: Boolean,
    text: String,
    isLoading: Boolean
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
            Text(
                text = text,
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
                    ),
                fontSize = 16.sp
            )
        }
    }
}

@[Composable Preview]
fun ChatScreenPreview() {
    ChatScreen(remember {
        mutableStateOf(
            ChatUiState(
                messages = listOf(
                    Message(
                        "1",
                        true,
                        "1"
                    ),
                    Message(
                        "2",
                        false,
                        "2"
                    )
                )
            )
        )
    }) { }
}
