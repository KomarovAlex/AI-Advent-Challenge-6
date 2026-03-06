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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
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
import ru.koalexse.aichallenge.ui.state.ContextStrategyType
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

    val messages by remember { derivedStateOf { currentUiState.messages } }
    val isLoading by remember { derivedStateOf { currentUiState.isLoading } }
    val currentInput by remember { derivedStateOf { currentUiState.currentInput } }
    val error by remember { derivedStateOf { currentUiState.error } }
    val sessionStats by remember { derivedStateOf { currentUiState.sessionStats } }
    val compressedMessageCount by remember { derivedStateOf { currentUiState.compressedMessageCount } }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

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
        MessageList(
            messages = messages,
            listState = listState,
            isAdvancingPhase = currentUiState.isAdvancingPhase,
            isLoading = isLoading,
            onIntent = currentOnIntent,
            modifier = Modifier.weight(1f)
        )

        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        SessionStatsFooter(
            uiState = currentUiState,
            sessionStats = sessionStats,
            compressedMessageCount = compressedMessageCount
        )

        Spacer(modifier = Modifier.height(8.dp))

        InputRow(
            currentInput = currentInput,
            isLoading = isLoading,
            onInputChange = remember { { text: String -> currentOnIntent(ChatIntent.UpdateInput(text)) } },
            onSendClick = remember { { currentOnIntent(ChatIntent.SendMessage(uiState.value.currentInput)) } }
        )
        Spacer(modifier = Modifier.height(16.dp))
    }

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
    uiState: ChatUiState,
    sessionStats: SessionTokenStats?,
    compressedMessageCount: Int,
    modifier: Modifier = Modifier
) {
    val hasBranchInfo = uiState.activeStrategy == ContextStrategyType.BRANCHING &&
            uiState.activeBranchId != null

    val activeBranchName = if (hasBranchInfo) {
        uiState.branches.find { it.id == uiState.activeBranchId }?.name
    } else null

    val hasContent = sessionStats != null || compressedMessageCount > 0 || activeBranchName != null

    if (hasContent) {
        Column(modifier = modifier.fillMaxWidth()) {
            HorizontalDivider(
                modifier = Modifier.padding(bottom = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            if (activeBranchName != null) {
                Text(
                    text = stringResource(
                        R.string.branch_stats_format,
                        activeBranchName,
                        uiState.branches.size
                    ),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

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

            if (compressedMessageCount > 0) {
                Text(
                    text = stringResource(R.string.compressed_stats_format, compressedMessageCount),
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
    isAdvancingPhase: Boolean,
    isLoading: Boolean,
    onIntent: (ChatIntent) -> Unit,
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
            when {
                // Task State bubble — Planning mode
                message.id == TASK_STATE_BUBBLE_ID ->
                    TaskStateBubble(
                        text = message.text,
                        isAdvancingPhase = isAdvancingPhase,
                        isLoading = isLoading,
                        onAdvancePhase = { onIntent(ChatIntent.AdvancePhase) },
                        onResetTask = { onIntent(ChatIntent.ResetTask) }
                    )

                // Layered Memory bubbles — распознаём по id
                message.id == "long_term_memory_bubble" ->
                    LongTermMemoryBubble(text = message.text)

                message.id == "working_memory_bubble" ->
                    WorkingMemoryBubble(text = message.text)

                // StickyFacts bubble
                message.id == "facts_bubble" ->
                    FactsBubble(text = message.text)

                // Все остальные сжатые сообщения
                message.isCompressed ->
                    CompressedMessageBubble(
                        isUser = message.isUser,
                        text = message.text
                    )

                // Обычное сообщение
                else ->
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
private fun ErrorDialog(error: String, onDismiss: () -> Unit) {
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

/**
 * Bubble состояния задачи (Planning mode).
 *
 * Отображает текущую фазу, шаг, ожидаемое действие, инварианты и итоги
 * завершённых фаз. Содержит кнопки:
 * - «→ Next phase» — ручной переход с LLM-валидацией
 * - «✕ Reset» — сброс (архивирование) текущей задачи
 *
 * Bubble самый верхний в ленте — пользователь всегда видит состояние задачи.
 * Обновляется по [AgentStreamEvent.Completed] (смена фазы/шага).
 */
@Composable
private fun TaskStateBubble(
    text: String,
    isAdvancingPhase: Boolean,
    isLoading: Boolean,
    onAdvancePhase: () -> Unit,
    onResetTask: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = "📋 Task state",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = text,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // → Next phase
                Button(
                    onClick = onAdvancePhase,
                    enabled = !isLoading && !isAdvancingPhase,
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 8.dp, vertical = 4.dp
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Text(
                        text = if (isAdvancingPhase) "Checking…" else "Next phase",
                        fontSize = 12.sp
                    )
                }
                // ✕ Reset
                OutlinedButton(
                    onClick = onResetTask,
                    enabled = !isLoading,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 8.dp, vertical = 4.dp
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Text(text = "Reset", fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * Бабл долговременной памяти 🧠 — профиль, решения, устойчивые знания.
 * Фиолетовый фон (tertiaryContainer), самый верхний в ленте.
 */
@Composable
private fun LongTermMemoryBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = "🧠 Long-term memory",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = text,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    }
}

/**
 * Бабл рабочей памяти 💼 — текущая задача, шаги, промежуточные результаты.
 * Синий фон (primaryContainer), под долговременной памятью.
 */
@Composable
private fun WorkingMemoryBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = "💼 Working memory",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = text,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

/**
 * Специальный бабл для отображения текущих фактов (StickyFacts-стратегия).
 * Фиксируется в начале ленты.
 */
@Composable
private fun FactsBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = stringResource(R.string.facts_bubble_label),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = text,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    }
}

/**
 * Пузырёк для обычного сообщения
 */
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
                        onClick = {},
                        onLongClick = {
                            if (!isLoading) clipboardManager.setText(AnnotatedString(text))
                        }
                    )
            ) {
                Text(text = text, fontSize = 16.sp)

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

/**
 * Пузырёк для сжатого сообщения — отображается с пометкой и приглушённо
 */
@Composable
private fun CompressedMessageBubble(
    isUser: Boolean,
    text: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .padding(horizontal = 4.dp, vertical = 2.dp)
                .alpha(0.5f),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(
                    text = stringResource(R.string.compressed_message_label),
                    fontSize = 10.sp,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = text,
                    fontSize = 14.sp,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurface
                )
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
                    Message("long_term_memory_bubble", false, "• имя: Алексей\n• язык: Kotlin\n• проект: aiChallenge", isCompressed = true),
                    Message("working_memory_bubble", false, "• текущая задача: реализовать LayeredMemory\n• шаг 1 статус: done\n• шаг 2 статус: in-progress", isCompressed = true),
                    Message("memory_c1", true, "Вытесненное сообщение 1", isCompressed = true),
                    Message("memory_c2", false, "Вытесненный ответ ассистента", isCompressed = true),
                    Message("1", true, "Как дела с задачей?"),
                    Message(
                        "2", false,
                        "Шаг 2 в процессе!",
                        tokenStats = TokenStats(150, 60, 210, timeToFirstTokenMs = 280),
                        responseDurationMs = 1800L
                    )
                ),
                settingsData = SettingsData("deepseek-v3.2", strategy = ContextStrategyType.LAYERED_MEMORY),
                sessionStats = SessionTokenStats(350, 150, 500, 2),
                compressedMessageCount = 2,
                activeStrategy = ContextStrategyType.LAYERED_MEMORY
            )
        )
    }) { }
}

@[Composable Preview]
fun TaskStateBubblePreview() {
    TaskStateBubble(
        text = "⚙️ Execution · Выполнение шагов задачи\nExpected: Выполните следующий шаг задачи\n\nCompleted phases:\n🗺️ Planning: Цель определена, декомпозиция готова…",
        isAdvancingPhase = false,
        isLoading = false,
        onAdvancePhase = {},
        onResetTask = {}
    )
}
