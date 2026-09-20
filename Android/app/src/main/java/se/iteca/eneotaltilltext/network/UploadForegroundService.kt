package se.iteca.eneotaltilltext.network

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import se.iteca.eneotaltilltext.EneoApplication
import se.iteca.eneotaltilltext.MainActivity
import se.iteca.eneotaltilltext.R
import se.iteca.eneotaltilltext.data.ErrorPresentation
import se.iteca.eneotaltilltext.notifications.TranscriptionNotificationService
import se.iteca.eneotaltilltext.util.Formatters
import kotlin.coroutines.cancellation.CancellationException

class UploadForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var uploadJob: Job? = null
    private var statusJob: Job? = null
    private var uploadToken = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uploadService = (application as EneoApplication).container.uploadService
        if (intent?.action == ACTION_CANCEL) {
            uploadService.cancelUpload()
            val job = uploadJob
            uploadJob?.cancel()
            if (job == null || job.isCompleted) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        val recordingId = intent?.getStringExtra(EXTRA_RECORDING_ID)
        val appId = intent?.getStringExtra(EXTRA_APP_ID).orEmpty()
        if (recordingId.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        uploadService.supersedeInFlight()
        uploadToken += 1
        val token = uploadToken
        uploadJob?.cancel()
        startInForeground(getString(R.string.status_uploading))
        statusJob?.cancel()
        statusJob = scope.launch {
            observeStatus()
        }
        uploadJob = scope.launch {
            try {
                runUpload(recordingId, appId, token)
            } finally {
                if (token == uploadToken) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun observeStatus() {
        val uploadService = (application as EneoApplication).container.uploadService
        uploadService.statusText.collectLatest { text ->
            if (!text.isNullOrBlank()) {
                startInForeground(text)
            }
        }
    }

    private suspend fun runUpload(recordingId: String, appId: String, token: Int) {
        val container = (application as EneoApplication).container
        val repository = container.repository
        val uploadService = container.uploadService
        val authService = container.authService
        val notifications = container.notifications
        val recording = repository.get(recordingId) ?: return
        val file = repository.fileFor(recording)
        val selectedAppId = appId.trim().ifEmpty {
            recording.uploadAppID?.trim().orEmpty().ifEmpty { container.apiSettings.appId }
        }
        var existingRunId = recording.uploadServerID?.trim()?.takeIf { it.isNotEmpty() }
        val runAppId = recording.uploadAppID?.trim().orEmpty()
        if (recording.hasTranscription) {
            repository.clearUploadRunId(recordingId)
            existingRunId = null
        } else if (existingRunId != null && runAppId.isNotEmpty() && runAppId != selectedAppId) {
            repository.clearUploadRunId(recordingId)
            existingRunId = null
        }
        val existingFileId = recording.uploadFileID?.trim()?.takeIf { it.isNotEmpty() }
        try {
            val response = uploadService.upload(
                audioFile = file,
                audioFileName = recording.audioFileName,
                selectedAppId = selectedAppId,
                recordingId = recordingId,
                existingFileId = existingFileId,
                existingRunId = existingRunId,
                onCheckpoint = { fileId, runId ->
                    if (token != uploadToken) return@upload
                    fileId?.let { repository.saveUploadFileId(recordingId, it) }
                    runId?.let { repository.saveUploadRunId(recordingId, it) }
                },
            )
            if (token != uploadToken) return
            val output = response.output
            val metadata = output?.let { uploadService.metadataFrom(it) }
            val latestTitle = repository.get(recordingId)?.title ?: recording.title
            val suggestedTitle = metadata?.suggestedTitle?.trim().orEmpty()
            val titleToApply = suggestedTitle.takeIf {
                it.isNotEmpty() && Formatters.isPlaceholderTitle(latestTitle)
            }
            repository.applyUploadResult(
                id = recordingId,
                fileId = response.fileId,
                serverId = response.id,
                status = response.status ?: "complete",
                uploadedAt = System.currentTimeMillis(),
                transcription = output,
                summary = metadata?.shortSummary,
                title = titleToApply,
                appId = selectedAppId.trim().ifEmpty { null },
            )
            if (!output.isNullOrBlank()) {
                notifications.notifyCompleted()
            }
        } catch (_: CancellationException) {
            if (token != uploadToken) return
            repository.resetCancelledUpload(recordingId)
        } catch (error: Exception) {
            if (token != uploadToken) return
            val presentation = ErrorPresentation.from(this, error)
            if (presentation.requiresSignIn) {
                authService.handleExpiredSession()
            }
            repository.setUploadFailed(recordingId, presentation.persistedText())
            notifications.notifyFailed()
        }
    }

    private fun startInForeground(text: String) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun buildNotification(text: String): Notification {
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, TranscriptionNotificationService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_upload_title))
            .setContentText(text)
            .setContentIntent(launch)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    override fun onDestroy() {
        uploadJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1002
        private const val EXTRA_RECORDING_ID = "recordingId"
        private const val EXTRA_APP_ID = "appId"
        private const val ACTION_CANCEL = "se.iteca.eneotaltilltext.CANCEL_UPLOAD"

        fun start(context: Context, recordingId: String, appId: String) {
            val intent = Intent(context, UploadForegroundService::class.java).apply {
                putExtra(EXTRA_RECORDING_ID, recordingId)
                putExtra(EXTRA_APP_ID, appId)
            }
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, UploadForegroundService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }
}
