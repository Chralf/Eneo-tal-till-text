package se.iteca.eneotaltilltext.ui.record

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import se.iteca.eneotaltilltext.R
import se.iteca.eneotaltilltext.audio.RecordingState
import se.iteca.eneotaltilltext.data.RecordingEntity
import se.iteca.eneotaltilltext.ui.theme.LocalWarningColor
import se.iteca.eneotaltilltext.util.Formatters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(
    onFinished: (RecordingEntity) -> Unit,
    onDismiss: () -> Unit,
    onOpenInfo: () -> Unit,
    viewModel: RecordViewModel = viewModel(factory = RecordViewModel.Factory),
) {
    val context = LocalContext.current
    val view = LocalView.current
    val recorder = viewModel.recorder
    val state by recorder.state.collectAsStateWithLifecycle()
    val recordingTime by recorder.recordingTime.collectAsStateWithLifecycle()
    val levels by recorder.audioLevels.collectAsStateWithLifecycle()
    val wasInterrupted by recorder.wasInterrupted.collectAsStateWithLifecycle()
    val reachedMax by recorder.reachedMaxDuration.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    var showPermissionAlert by remember { mutableStateOf(false) }
    var showMaxDurationAlert by remember { mutableStateOf(false) }
    var showDiscardConfirmation by remember { mutableStateOf(false) }
    var finishedFromMax by remember { mutableStateOf<RecordingEntity?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.containsKey(Manifest.permission.RECORD_AUDIO)) {
            permissionGranted = result[Manifest.permission.RECORD_AUDIO] == true
            if (!permissionGranted) showPermissionAlert = true
        }
    }

    LaunchedEffect(Unit) {
        val needed = buildList {
            if (!permissionGranted) add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    DisposableEffect(state) {
        val keepAwake = state != RecordingState.Idle
        val activity = context as? android.app.Activity
        val window = activity?.window
        view.keepScreenOn = keepAwake
        if (keepAwake) {
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            view.keepScreenOn = false
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    BackHandler(enabled = state != RecordingState.Idle) {
        showDiscardConfirmation = true
    }

    LaunchedEffect(reachedMax) {
        if (reachedMax) {
            val saved = viewModel.finalizeRecording(fromMaxDuration = true)
            if (saved != null) {
                finishedFromMax = saved
                showMaxDurationAlert = true
            }
        }
    }

    fun finishAndClose() {
        val saved = viewModel.finalizeRecording(fromMaxDuration = false)
        if (saved != null) onFinished(saved) else onDismiss()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.record_title)) },
                navigationIcon = {
                    TextButton(
                        onClick = {
                            if (state != RecordingState.Idle) {
                                showDiscardConfirmation = true
                            } else {
                                onDismiss()
                            }
                        },
                    ) {
                        Text(if (state != RecordingState.Idle) stringResource(R.string.cancel) else stringResource(R.string.close))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Text(
                Formatters.recordingTimer(recordingTime),
                fontSize = 64.sp,
                fontWeight = FontWeight.Light,
                fontFamily = FontFamily.Monospace,
                color = if (state == RecordingState.Recording) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.height(24.dp))
            WaveformView(
                levels = levels,
                barColor = if (state == RecordingState.Recording) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
            )
            if (state == RecordingState.Recording || state == RecordingState.Paused) {
                Spacer(Modifier.height(16.dp))
                StatusLabel(state = state, wasInterrupted = wasInterrupted)
            }
            if (recordingTime >= RecordingEntity.MAX_RECORDING_DURATION_SECONDS - 60 &&
                state != RecordingState.Idle
            ) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.record_max_duration_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalWarningColor.current,
                )
            }
            Spacer(Modifier.weight(1f))
            ControlButtons(
                state = state,
                permissionGranted = permissionGranted,
                onStart = { viewModel.startRecording() },
                onPause = { viewModel.pause() },
                onResume = { viewModel.resume() },
                onStop = { finishAndClose() },
            )
            Spacer(Modifier.height(16.dp))
            AnimatedVisibility(
                visible = state == RecordingState.Idle,
                enter = fadeIn() + slideInVertically { it / 3 },
                exit = fadeOut() + slideOutVertically { it / 3 },
            ) {
                IdleTips(onOpenInfo = onOpenInfo)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = { Text(stringResource(R.string.record_error_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError() }) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }

    if (showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmation = false },
            title = { Text(stringResource(R.string.record_discard_title)) },
            text = { Text(stringResource(R.string.record_discard_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirmation = false
                        viewModel.cancel()
                        onDismiss()
                    },
                ) {
                    Text(
                        stringResource(R.string.record_discard_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirmation = false }) {
                    Text(stringResource(R.string.record_discard_keep))
                }
            },
        )
    }

    if (showPermissionAlert) {
        AlertDialog(
            onDismissRequest = { showPermissionAlert = false; onDismiss() },
            title = { Text(stringResource(R.string.record_permission_title)) },
            text = {
                Text(stringResource(R.string.record_permission_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            },
                        )
                    },
                ) { Text(stringResource(R.string.record_open_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionAlert = false; onDismiss() }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showMaxDurationAlert) {
        fun closeAfterMax() {
            showMaxDurationAlert = false
            val saved = finishedFromMax
            if (saved != null) onFinished(saved) else onDismiss()
        }
        AlertDialog(
            onDismissRequest = { closeAfterMax() },
            title = { Text(stringResource(R.string.record_max_duration_title)) },
            text = { Text(stringResource(R.string.record_max_duration_message)) },
            confirmButton = {
                TextButton(onClick = { closeAfterMax() }) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }
}

@Composable
private fun IdleTips(onOpenInfo: () -> Unit) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val before = stringResource(R.string.record_read_info_before)
    val link = stringResource(R.string.record_read_info_link)
    val after = stringResource(R.string.record_read_info_after)
    val readInfoA11y = stringResource(R.string.record_read_info_a11y)
    val annotated = buildAnnotatedString {
        append(before)
        withStyle(
            SpanStyle(
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
            ),
        ) {
            append(link)
        }
        append(after)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenInfo)
                .semantics { contentDescription = readInfoA11y },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = secondary,
                modifier = Modifier.size(18.dp).padding(top = 2.dp),
            )
            Text(
                annotated,
                style = MaterialTheme.typography.bodySmall.copy(color = secondary),
                textAlign = TextAlign.Start,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.PhoneAndroid,
                contentDescription = null,
                tint = secondary,
                modifier = Modifier.size(18.dp).padding(top = 2.dp),
            )
            Text(
                stringResource(R.string.record_keep_open),
                style = MaterialTheme.typography.bodySmall,
                color = secondary,
                textAlign = TextAlign.Start,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatusLabel(state: RecordingState, wasInterrupted: Boolean) {
    val text = when {
        wasInterrupted -> stringResource(R.string.record_status_interrupted)
        state == RecordingState.Recording -> stringResource(R.string.record_status_recording)
        else -> stringResource(R.string.record_status_paused)
    }
    val color = if (state == RecordingState.Recording) Color.Red else LocalWarningColor.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ControlButtons(
    state: RecordingState,
    permissionGranted: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (state) {
            RecordingState.Idle -> {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(if (permissionGranted) Color.Red else Color.Red.copy(alpha = 0.4f))
                        .clickable(enabled = permissionGranted, onClick = onStart),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color.Red),
                    )
                }
            }
            RecordingState.Recording -> {
                Icon(
                    Icons.Filled.PauseCircle,
                    contentDescription = stringResource(R.string.record_content_desc_pause),
                    tint = LocalWarningColor.current,
                    modifier = Modifier.size(52.dp).clickable(onClick = onPause),
                )
                Icon(
                    Icons.Filled.StopCircle,
                    contentDescription = stringResource(R.string.record_content_desc_stop),
                    tint = Color.Red,
                    modifier = Modifier.size(72.dp).clickable(onClick = onStop),
                )
            }
            RecordingState.Paused -> {
                Icon(
                    Icons.Filled.PlayCircle,
                    contentDescription = stringResource(R.string.record_content_desc_resume),
                    tint = Color(0xFF34C759),
                    modifier = Modifier.size(52.dp).clickable(onClick = onResume),
                )
                Icon(
                    Icons.Filled.StopCircle,
                    contentDescription = stringResource(R.string.record_content_desc_stop),
                    tint = Color.Red,
                    modifier = Modifier.size(72.dp).clickable(onClick = onStop),
                )
            }
        }
    }
}
