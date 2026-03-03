package ru.koalexse.aichallenge.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.koalexse.aichallenge.R

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProfileEditScreen(
    profileId: String,
    viewModel: ProfileEditViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    // Загружаем профиль при первом появлении экрана
    LaunchedEffect(profileId) {
        viewModel.handleIntent(ProfileEditIntent.Load(profileId))
    }

    // После успешного сохранения — возвращаемся назад
    LaunchedEffect(state.isSaved) {
        if (state.isSaved) {
            viewModel.handleIntent(ProfileEditIntent.ClearSaved)
            onBack()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.profile.name.ifBlank {
                            stringResource(R.string.profile_new_title)
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (state.isLoading && !state.isExtracting) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {

                    // ── Название ──────────────────────────────────────────────
                    OutlinedTextField(
                        value = state.profile.name,
                        onValueChange = {
                            viewModel.handleIntent(ProfileEditIntent.UpdateName(it))
                        },
                        label = { Text(stringResource(R.string.profile_name_label)) },
                        singleLine = true,
                        enabled = !state.profile.isDefault,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // ── Текстовое поле (rawText) ──────────────────────────────
                    OutlinedTextField(
                        value = state.profile.rawText,
                        onValueChange = {
                            viewModel.handleIntent(ProfileEditIntent.UpdateRawText(it))
                        },
                        label = { Text(stringResource(R.string.profile_raw_text_label)) },
                        minLines = 5,
                        maxLines = 12,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // ── Кнопка «Извлечь факты» ────────────────────────────────
                    OutlinedButton(
                        onClick = { viewModel.handleIntent(ProfileEditIntent.ExtractFacts) },
                        enabled = state.profile.rawText.isNotBlank()
                                && !state.isExtracting
                                && !state.isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.isExtracting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.profile_extracting_facts))
                        } else {
                            Text(stringResource(R.string.profile_extract_facts))
                        }
                    }

                    // ── Извлечённые факты ─────────────────────────────────────
                    Column {
                        Text(
                            text = stringResource(R.string.profile_facts_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))

                        if (state.profile.facts.isEmpty()) {
                            Text(
                                text = stringResource(R.string.profile_no_facts),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                state.profile.facts.forEach { fact ->
                                    AssistChip(
                                        onClick = {},
                                        label = {
                                            Text(
                                                text = fact,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // ── Ошибка ────────────────────────────────────────────────
                    state.error?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // ── Кнопка Сохранить ──────────────────────────────────────
                    // При наличии rawText перед сохранением автоматически запускается
                    // извлечение фактов внутри ViewModel.save().
                    Button(
                        onClick = { viewModel.handleIntent(ProfileEditIntent.Save) },
                        enabled = !state.isLoading && !state.isExtracting,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.isExtracting || state.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.profile_save))
                    }
                }
            }
        }
    }
}
