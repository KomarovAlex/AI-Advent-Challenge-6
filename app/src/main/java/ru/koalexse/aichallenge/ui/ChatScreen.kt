package ru.koalexse.aichallenge.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
import ru.koalexse.aichallenge.domain.Message
import ru.koalexse.aichallenge.ui.state.ChatUiState

@OptIn(FlowPreview::class)
@Composable
fun ChatScreen(
    uiState: State<ChatUiState>,
    onIntent: (ChatIntent) -> Unit,
) {
    val state by uiState
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Автоскролл при добавлении новых сообщений
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    // Следим за последним сообщением
    val lastMessage = state.messages.lastOrNull()
    LaunchedEffect(lastMessage?.text) {
        lastMessage?.let {
            if (it.isLoading && state.messages.isNotEmpty()) {
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
        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            reverseLayout = true
        ) {
            items(state.messages.reversed(), key = { it.id }) { message ->
                MessageBubble(message = message)
            }
        }

        // Индикатор загрузки
        if (state.isLoading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Поле ввода сообщения
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = state.currentInput,
                onValueChange = { onIntent(ChatIntent.UpdateInput(it)) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message...") },
                enabled = !state.isLoading,
                maxLines = 3
            )

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = {
                    onIntent(ChatIntent.SendMessage(state.currentInput))
                    scope.launch {
                        listState.animateScrollToItem(state.messages.size)
                    }
                },
                enabled = state.currentInput.isNotBlank() &&
                        !state.isLoading
            ) {
                Text("Send")
            }
        }
    }

    // Ошибка
    if (state.error != null) {
        AlertDialog(
            onDismissRequest = { onIntent(ChatIntent.ClearError) },
            title = { Text("Error") },
            text = { Text(state.error!!) },
            confirmButton = {
                TextButton(onClick = { onIntent(ChatIntent.ClearError) }) {
                    Text("OK")
                }
            }
        )
    }
}

@Composable
fun MessageBubble(message: Message) {
    val isUser = message.isUser

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
                text = message.text,
                modifier = Modifier
                    .padding(12.dp)
                    .clickable {
                        if (!message.isLoading) {
                            clipboardManager.setText(AnnotatedString(message.text))
                        }
                    },
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
