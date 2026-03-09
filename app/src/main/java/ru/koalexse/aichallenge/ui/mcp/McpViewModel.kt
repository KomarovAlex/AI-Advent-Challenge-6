package ru.koalexse.aichallenge.ui.mcp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.koalexse.aichallenge.agent.mcp.McpClient
import ru.koalexse.aichallenge.domain.McpTool

data class McpUiState(
    val isLoading: Boolean = false,
    val tools: List<McpTool>? = null,   // null = не запрашивались
    val error: String? = null
)

class McpViewModel(
    private val mcpClient: McpClient
) : ViewModel() {

    private val _state = MutableStateFlow(McpUiState())
    val state: StateFlow<McpUiState> = _state.asStateFlow()

    fun loadTools() {
        if (_state.value.isLoading) return
        _state.value = McpUiState(isLoading = true)
        viewModelScope.launch {
            runCatching { mcpClient.listTools() }
                .onSuccess { tools ->
                    _state.value = McpUiState(tools = tools)
                }
                .onFailure { e ->
                    _state.value = McpUiState(error = e.message ?: "Unknown error")
                }
        }
    }
}
