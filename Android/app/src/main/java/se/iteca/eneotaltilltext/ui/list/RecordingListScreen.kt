package se.iteca.eneotaltilltext.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import se.iteca.eneotaltilltext.R
import se.iteca.eneotaltilltext.ui.theme.LocalWarningColor
import se.iteca.eneotaltilltext.data.RecordingEntity
import se.iteca.eneotaltilltext.data.TranscriptionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingListScreen(
    onOpenSettings: () -> Unit,
    onOpenInfo: () -> Unit,
    onRecord: () -> Unit,
    onOpenRecording: (String) -> Unit,
    viewModel: RecordingListViewModel = viewModel(factory = RecordingListViewModel.Factory),
) {
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val grouped = MaterialTheme.colorScheme.surfaceContainer
    val card = MaterialTheme.colorScheme.surfaceContainerLowest

    Scaffold(
        containerColor = grouped,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.list_title)) },
                navigationIcon = {
                    IconButton(onClick = onOpenInfo) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = stringResource(R.string.info_title),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                },
            )
        },
        bottomBar = {
            Column(
                Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding(),
            ) {
                HorizontalDivider()
                Button(
                    onClick = onRecord,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .height(52.dp),
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = null)
                    Spacer(Modifier.padding(horizontal = 6.dp))
                    Text(stringResource(R.string.list_record_button), style = MaterialTheme.typography.titleMedium)
                }
            }
        },
    ) { padding ->
        if (recordings.isEmpty()) {
            EmptyState(Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(recordings, key = { it.id }) { recording ->
                    val dismissState = rememberSwipeToDismissBoxState()
                    LaunchedEffect(dismissState.currentValue) {
                        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
                            viewModel.delete(recording)
                        }
                    }
                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .padding(vertical = 6.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.error),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.delete),
                                    tint = MaterialTheme.colorScheme.onError,
                                    modifier = Modifier.padding(end = 20.dp),
                                )
                            }
                        },
                        enableDismissFromStartToEnd = false,
                    ) {
                        RecordingRow(
                            recording = recording,
                            cardColor = card,
                            onClick = { onOpenRecording(recording.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                Icons.Filled.GraphicEq,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.list_empty_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.list_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecordingRow(
    recording: RecordingEntity,
    cardColor: Color,
    onClick: () -> Unit,
) {
    val importedLabel = stringResource(R.string.list_imported)
    val statusDescription = when (recording.transcriptionState) {
        TranscriptionState.None -> ""
        TranscriptionState.Processing -> stringResource(R.string.list_status_processing)
        TranscriptionState.Failed -> stringResource(R.string.list_status_failed)
        TranscriptionState.Completed -> stringResource(R.string.list_status_completed)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(cardColor)
            .clickable(onClick = onClick)
            .padding(16.dp)
            .semantics {
                contentDescription = listOfNotNull(
                    recording.title,
                    recording.formattedDate,
                    recording.formattedDuration,
                    if (recording.isImported) importedLabel else null,
                    statusDescription.takeIf { it.isNotEmpty() },
                    recording.summaryText?.trim()?.takeIf { it.isNotEmpty() },
                ).joinToString(", ")
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            recording.title,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 2,
        )
        val summary = recording.summaryText?.trim().orEmpty()
        if (summary.isNotEmpty()) {
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                recording.formattedDate,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                recording.formattedDuration,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StatusIcons(recording)
        }
    }
}

@Composable
private fun StatusIcons(recording: RecordingEntity) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (recording.isImported) {
            Icon(
                Icons.Filled.Download,
                contentDescription = stringResource(R.string.list_imported),
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (recording.transcriptionState) {
            TranscriptionState.None -> Unit
            TranscriptionState.Processing -> CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
            )
            TranscriptionState.Failed -> Icon(
                Icons.Filled.Warning,
                contentDescription = stringResource(R.string.list_status_failed),
                modifier = Modifier.size(14.dp),
                tint = LocalWarningColor.current,
            )
            TranscriptionState.Completed -> Icon(
                Icons.AutoMirrored.Filled.Article,
                contentDescription = stringResource(R.string.list_status_completed),
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
