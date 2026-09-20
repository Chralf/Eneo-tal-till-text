package se.iteca.eneotaltilltext.ui.detail

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import android.view.WindowManager
import se.iteca.eneotaltilltext.R
import se.iteca.eneotaltilltext.data.ErrorPresentation
import se.iteca.eneotaltilltext.data.RecordingEntity
import se.iteca.eneotaltilltext.markdown.MarkdownClipboard
import se.iteca.eneotaltilltext.markdown.MarkdownExport
import se.iteca.eneotaltilltext.ui.auth.EneoConnectSheet
import se.iteca.eneotaltilltext.ui.components.EneoAppPickerDialog
import se.iteca.eneotaltilltext.util.Formatters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingDetailScreen(
    recordingId: String,
    onBack: () -> Unit,
    viewModel: RecordingDetailViewModel = viewModel(
        key = recordingId,
        factory = RecordingDetailViewModel.factory(recordingId),
    ),
) {
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val isUploading by viewModel.uploadService.isUploading.collectAsStateWithLifecycle()
    val uploadingId by viewModel.uploadService.uploadingRecordingId.collectAsStateWithLifecycle()
    val statusText by viewModel.uploadService.statusText.collectAsStateWithLifecycle()
    val selectedAppId by viewModel.selectedAppId.collectAsStateWithLifecycle()
    val availableApps by viewModel.availableApps.collectAsStateWithLifecycle()
    val personalSpaceId by viewModel.personalSpaceId.collectAsStateWithLifecycle()
    val isLoadingApps by viewModel.isLoadingApps.collectAsStateWithLifecycle()
    val appLoadError by viewModel.appLoadError.collectAsStateWithLifecycle()
    val preflightError by viewModel.preflightError.collectAsStateWithLifecycle()
    val isCheckingServer by viewModel.isCheckingServer.collectAsStateWithLifecycle()
    val isPlaying by viewModel.player.isPlaying.collectAsStateWithLifecycle()
    val position by viewModel.player.position.collectAsStateWithLifecycle()
    val duration by viewModel.player.duration.collectAsStateWithLifecycle()
    val playbackError by viewModel.player.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val shareResultChooserTitle = stringResource(R.string.detail_action_share_result)

    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    var showDelete by remember { mutableStateOf(false) }
    var showConnect by remember { mutableStateOf(false) }
    var pendingUploadAfterLogin by remember { mutableStateOf(false) }
    var pendingOpenAppPickerAfterLogin by remember { mutableStateOf(false) }
    var showAppPicker by remember { mutableStateOf(false) }
    var isInfoExpanded by remember { mutableStateOf(false) }
    var isPlaybackExpanded by remember { mutableStateOf(false) }
    var titleText by remember { mutableStateOf("") }

    val uploadingThis = isUploading && uploadingId == recordingId

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.upload() }

    val current = recording ?: return
    LaunchedEffect(current.id, current.title) {
        if (titleText.trim() != current.title) {
            titleText = current.title
        }
    }

    fun openAppPickerAfterAuth() {
        viewModel.loadApps()
        showAppPicker = true
    }

    fun beginSelectApp() {
        viewModel.withServerReachable(forAppSelection = true) {
            if (!viewModel.authService.isAuthenticated.value) {
                pendingOpenAppPickerAfterLogin = true
                pendingUploadAfterLogin = false
                showConnect = true
            } else {
                openAppPickerAfterAuth()
            }
        }
    }

    fun requestUpload() {
        viewModel.clearPreflightError()
        viewModel.withServerReachable(forAppSelection = false) {
            if (!viewModel.authService.isAuthenticated.value) {
                pendingUploadAfterLogin = true
                pendingOpenAppPickerAfterLogin = false
                viewModel.clearUploadError()
                showConnect = true
                return@withServerReachable
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.upload()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(current.title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.close),
                        )
                    }
                },
            )
        },
        bottomBar = {
            Column(
                Modifier
                    .navigationBarsPadding(),
            ) {
                HorizontalDivider()
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (uploadingThis) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(
                                statusText ?: current.uploadProgressText ?: stringResource(R.string.detail_working),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(onClick = { viewModel.cancelUpload() }) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    } else {
                        Button(
                            onClick = { requestUpload() },
                            enabled = viewModel.audioExists(current) && !isUploading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                        ) {
                            Icon(Icons.Filled.Upload, contentDescription = null)
                            Spacer(Modifier.padding(horizontal = 8.dp))
                            Text(
                                if (current.hasTranscription) {
                                    stringResource(R.string.detail_convert_again)
                                } else {
                                    stringResource(R.string.detail_convert)
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = titleText,
                onValueChange = {
                    titleText = it
                    viewModel.saveTitle(it)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.detail_title_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { viewModel.saveTitle(titleText) }),
            )

            ExpandableSection(
                title = stringResource(R.string.detail_section_info),
                expanded = isInfoExpanded,
                onToggle = { isInfoExpanded = !isInfoExpanded },
            ) {
                InfoRow(stringResource(R.string.detail_info_created), current.formattedDate)
                InfoRow(stringResource(R.string.detail_info_duration), current.formattedDuration)
                if (current.isImported) {
                    InfoRow(stringResource(R.string.detail_info_source), stringResource(R.string.list_imported))
                }
                InfoRow(stringResource(R.string.detail_info_file), current.audioFileName)
                current.uploadedAt?.let {
                    InfoRow(stringResource(R.string.detail_info_uploaded), Formatters.abbreviatedDateTime(it))
                }
            }

            if (!viewModel.audioExists(current)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        stringResource(R.string.detail_audio_missing_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.detail_audio_missing_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            ExpandableSection(
                title = stringResource(R.string.detail_section_playback),
                expanded = isPlaybackExpanded,
                onToggle = { isPlaybackExpanded = !isPlaybackExpanded },
            ) {
                TextButton(
                    onClick = { viewModel.togglePlayback() },
                    enabled = viewModel.audioExists(current),
                ) {
                    Icon(
                        if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = null,
                    )
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text(
                        if (isPlaying) {
                            stringResource(R.string.detail_action_stop)
                        } else {
                            stringResource(R.string.detail_action_play)
                        },
                    )
                }
                Slider(
                    value = position.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
                    onValueChange = {
                        viewModel.setScrubbing(true)
                        viewModel.seek(it.toDouble())
                    },
                    onValueChangeFinished = { viewModel.setScrubbing(false) },
                    valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                    enabled = viewModel.audioExists(current),
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        Formatters.playbackTime(position),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        Formatters.playbackTime(duration),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isCheckingServer) { beginSelectApp() }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    viewModel.selectedAppDisplayName(current),
                    modifier = Modifier.weight(1f),
                )
                if (isCheckingServer || isLoadingApps) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.ChevronRight, contentDescription = null)
                }
            }
            val resultText = current.transcriptionText?.trim().orEmpty()
            if (resultText.isNotEmpty()) {
                val export = remember(resultText) { MarkdownExport.fromMarkdown(resultText) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.detail_section_result),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = { MarkdownClipboard.copyResult(context, resultText) },
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = stringResource(R.string.detail_action_copy_result),
                        )
                    }
                    IconButton(
                        onClick = {
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, current.title)
                                putExtra(Intent.EXTRA_TEXT, export.plain)
                            }
                            context.startActivity(
                                Intent.createChooser(share, shareResultChooserTitle),
                            )
                        },
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.detail_action_share_result))
                    }
                }
                MarkdownResultView(resultText)
            }

            OutlinedButton(
                onClick = { showDelete = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(top = 8.dp),
            ) {
                Text(
                    stringResource(R.string.delete),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }


    val playbackNoticeTitle = stringResource(R.string.detail_playback_error_title)
    val playbackNoticeMessage = stringResource(R.string.detail_playback_error_message)
    var playbackNotice by remember { mutableStateOf<ErrorPresentation?>(null) }
    LaunchedEffect(playbackError) {
        if (playbackError != null) {
            playbackNotice = ErrorPresentation(
                title = playbackNoticeTitle,
                message = playbackNoticeMessage,
            )
        }
    }
    val notice = playbackNotice
        ?: appLoadError
        ?: preflightError
        ?: viewModel.uploadErrorPresentation(current)?.takeIf { !uploadingThis }

    if (notice != null) {
        AlertDialog(
            onDismissRequest = {
                playbackNotice = null
                viewModel.player.clearError()
                viewModel.dismissNotice()
            },
            title = { Text(notice.title) },
            text = {
                notice.message?.takeIf { it.isNotBlank() }?.let { Text(it) }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        playbackNotice = null
                        viewModel.player.clearError()
                        viewModel.dismissNotice()
                    },
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }

    if (showConnect) {
        EneoConnectSheet(
            authService = viewModel.authService,
            settingsStore = viewModel.settingsStore,
            onFinished = { signedIn ->
                showConnect = false
                if (!signedIn) {
                    pendingUploadAfterLogin = false
                    pendingOpenAppPickerAfterLogin = false
                    return@EneoConnectSheet
                }
                if (pendingUploadAfterLogin) {
                    pendingUploadAfterLogin = false
                    pendingOpenAppPickerAfterLogin = false
                    requestUpload()
                } else if (pendingOpenAppPickerAfterLogin) {
                    pendingOpenAppPickerAfterLogin = false
                    openAppPickerAfterAuth()
                }
            },
        )
    }

    if (showAppPicker) {
        EneoAppPickerDialog(
            apps = availableApps,
            selectedAppId = selectedAppId,
            personalSpaceId = personalSpaceId,
            onSelect = viewModel::setSelectedApp,
            onDismiss = { showAppPicker = false },
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(R.string.detail_delete_title)) },
            text = { Text(stringResource(R.string.detail_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    viewModel.delete(onBack)
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun ExpandableSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                content()
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            modifier = Modifier.padding(start = 12.dp).weight(1f, fill = false),
            maxLines = 2,
        )
    }
}
