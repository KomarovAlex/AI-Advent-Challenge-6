package ru.koalexse.aichallenge.ui.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.koalexse.aichallenge.domain.McpTool

/**
 * Диалог со списком MCP-инструментов.
 *
 * При первом открытии автоматически вызывает [McpViewModel.loadTools].
 * Показывает прогресс-индикатор, список инструментов или сообщение об ошибке.
 */
@Composable
fun McpToolsDialog(
    viewModel: McpViewModel,
    onDismiss: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    // Загружаем при открытии, если ещё не загружали
    LaunchedEffect(Unit) {
        if (state.tools == null && !state.isLoading) {
            viewModel.loadTools()
        }
    }

    McpToolsDialogContent(
        state = state,
        onDismiss = onDismiss,
        onRetry = { viewModel.loadTools() }
    )
}

/**
 * Stateless-версия диалога — используется и в McpToolsDialog, и в Preview.
 */
@Composable
fun McpToolsDialogContent(
    state: McpUiState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🔧 MCP Tools") },
        text = {
            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                state.error != null -> {
                    Text(
                        text = "❌ ${state.error}",
                        color = MaterialTheme.colorScheme.error
                    )
                }

                state.tools.isNullOrEmpty() -> {
                    Text(
                        text = "No tools available",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> {
                    val tools = state.tools.orEmpty()
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(tools) { tool ->
                            ToolItem(tool)
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            if (state.error != null) {
                TextButton(onClick = onRetry) { Text("Retry") }
            }
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun ToolItem(tool: McpTool) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = tool.name,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp
        )
        if (!tool.description.isNullOrBlank()) {
            Text(
                text = tool.description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ==================== Previews ====================

@Preview
@Composable
private fun McpToolsDialogLoadingPreview() {
    McpToolsDialogContent(
        state = McpUiState(isLoading = true),
        onDismiss = {},
        onRetry = {}
    )
}

@Preview
@Composable
private fun McpToolsDialogSuccessPreview() {
    McpToolsDialogContent(
        state = McpUiState(
            tools = listOf(
                McpTool("search_products", "Поиск товаров в каталоге ВкусВилл"),
                McpTool("get_product_info", "Получить информацию о товаре по ID"),
                McpTool("get_stores", "Список ближайших магазинов"),
            )
        ),
        onDismiss = {},
        onRetry = {}
    )
}

@Preview
@Composable
private fun McpToolsDialogErrorPreview() {
    McpToolsDialogContent(
        state = McpUiState(error = "Connection refused: mcp001.vkusvill.ru"),
        onDismiss = {},
        onRetry = {}
    )
}
