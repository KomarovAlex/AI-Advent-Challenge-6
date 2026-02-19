package ru.koalexse.aichallenge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

data class SettingsData(
    val temperature: String? = null,
    val tokens: String? = null,
)

fun SettingsData.isEmpty() = temperature.isNullOrEmpty() && tokens.isNullOrEmpty()

@Composable
fun MultiFieldInputDialog(
    settings: SettingsData = SettingsData(),
    onDismiss: () -> Unit,
    onConfirm: (SettingsData) -> Unit
) {
    var settingsData by remember { mutableStateOf(settings) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = settingsData.temperature ?: "",
                    onValueChange = { settingsData = settingsData.copy(temperature = it) },
                    label = { Text("temperature") },
                    singleLine = true
                )

                OutlinedTextField(
                    value = settingsData.tokens ?: "",
                    onValueChange = { settingsData = settingsData.copy(tokens = it) },
                    label = { Text("max tokens") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(settingsData)
                },
                enabled = !settingsData.isEmpty()
            ) {
                Text("save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("cancel")
            }
        }
    )
}

@[Composable Preview]
fun MultiFieldInputDialogPreview() {
    MultiFieldInputDialog(onDismiss = {}) {}
}