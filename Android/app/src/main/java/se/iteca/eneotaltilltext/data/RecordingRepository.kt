package se.iteca.eneotaltilltext.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.iteca.eneotaltilltext.audio.AudioRecorder
import se.iteca.eneotaltilltext.audio.RecordingState
import se.iteca.eneotaltilltext.util.Formatters
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class RecordingRepository(
    private val context: Context,
    private val dao: RecordingDao,
    private val fileStore: RecordingFileStore,
    private val settingsStore: ApiSettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeRecordingId = AtomicReference<String?>(null)

    fun observeAll(): Flow<List<RecordingEntity>> = dao.observeAll()

    fun observe(id: String): Flow<RecordingEntity?> = dao.observeById(id)

    suspend fun get(id: String): RecordingEntity? = dao.getById(id)

    suspend fun insert(recording: RecordingEntity) = dao.insert(recording)

    suspend fun update(recording: RecordingEntity) = dao.update(recording)

    suspend fun updateTitle(id: String, title: String) = dao.updateTitle(id, title)

    suspend fun updateSelectedApp(id: String, appId: String, appName: String?, spaceName: String?) =
        dao.updateSelectedApp(id, appId, appName, spaceName)

    suspend fun clearUploadError(id: String) = dao.clearUploadError(id)

    suspend fun startUpload(id: String, status: String, progress: String?, appId: String) =
        dao.startUpload(id, status, progress, appId)

    suspend fun applyUploadResult(
        id: String,
        fileId: String?,
        serverId: String?,
        status: String,
        uploadedAt: Long,
        transcription: String?,
        summary: String?,
        title: String?,
        appId: String?,
    ) = dao.applyUploadResult(id, fileId, serverId, status, uploadedAt, transcription, summary, title, appId)

    suspend fun resetCancelledUpload(id: String) = dao.resetCancelledUpload(id)

    suspend fun clearUploadRunId(id: String) = dao.clearUploadRunId(id)

    suspend fun saveUploadFileId(id: String, fileId: String) = dao.saveUploadFileId(id, fileId)

    suspend fun saveUploadRunId(id: String, runId: String) = dao.saveUploadRunId(id, runId)

    suspend fun setUploadFailed(id: String, error: String) = dao.setUploadFailed(id, error)

    suspend fun delete(recording: RecordingEntity) {
        fileStore.delete(recording)
        fileStore.deleteSessionPartFiles(recording.audioFileName)
        dao.delete(recording)
    }

    fun fileFor(recording: RecordingEntity) = fileStore.fileFor(recording)

    fun audioExists(recording: RecordingEntity) = fileStore.exists(recording)

    fun createRecordingFile() = fileStore.createRecordingFile()

    fun setActiveRecordingId(id: String?) {
        activeRecordingId.set(id)
    }

    fun pendingPartFiles(recording: RecordingEntity): List<File> =
        fileStore.pendingPartFiles(recording.audioFileName)

    /**
     * Merges leftover segments when a recording's final file is missing.
     * Parts stay on disk if the merge fails.
     */
    suspend fun recoverAudioFile(recording: RecordingEntity): Boolean = withContext(Dispatchers.IO) {
        if (fileStore.exists(recording)) return@withContext true
        val session = fileStore.mergePendingParts(recording.audioFileName) ?: return@withContext false
        if (session.duration > 0) {
            dao.update(recording.copy(duration = session.duration))
        }
        true
    }

    /**
     * Stops an in-progress recording when the user removes the app from Recents,
     * concatenates segments, and persists the final duration.
     * The merge itself runs on [Dispatchers.IO] inside [AudioRecorder.stop].
     */
    suspend fun finalizeRecordingIfActive(recorder: AudioRecorder): Boolean {
        if (recorder.state.value == RecordingState.Idle && !recorder.isFinalizing.value) return false
        val fileName = recorder.sessionFinalFile?.name
        recorder.onSegmentFinalized = null
        val duration = recorder.stop()
        val id = activeRecordingId.getAndSet(null)
        val existing = when {
            id != null -> dao.getById(id)
            fileName != null -> dao.getByAudioFileName(fileName)
            else -> null
        }
        if (existing != null) {
            dao.update(existing.copy(duration = duration))
        }
        return true
    }

    fun deleteSessionPartFiles(finalFileName: String) =
        fileStore.deleteSessionPartFiles(finalFileName)

    suspend fun importFromUri(uri: Uri): RecordingEntity {
        val imported = fileStore.importFromUri(uri)
        val recording = RecordingEntity(
            title = imported.title,
            audioFileName = imported.file.name,
            duration = imported.duration,
            isImported = true,
        )
        dao.insert(recording)
        return recording
    }

    fun recoverOnLaunch(recorder: AudioRecorder? = null) {
        scope.launch {
            dao.resetStuckTranscribingFlags()
            recoverSegmentSessions(recorder)
            refreshMissingDurations()
            recoverOrphanAudioFiles()
            purgeExpiredRecordings()
        }
    }

    /**
     * Applies the retention setting. Also run when the app returns to the
     * foreground: Android can keep the process alive for days, so launch alone
     * would let expired audio linger past the configured limit.
     */
    fun purgeExpiredOnForeground() {
        scope.launch {
            purgeExpiredRecordings()
        }
    }

    suspend fun countWouldExpire(withAppDefaultDays: Int): Int {
        val now = System.currentTimeMillis()
        return dao.getAll().count { recording ->
            if (recording.isTranscribing || recording.uploadStatus == "processing") {
                false
            } else {
                RecordingRetention.isExpired(
                    dateCreatedMillis = recording.dateCreated,
                    retentionDays = withAppDefaultDays,
                    nowMillis = now,
                )
            }
        }
    }

    private suspend fun purgeExpiredRecordings() {
        val retentionDays = settingsStore.retentionDays
        val now = System.currentTimeMillis()
        dao.getAll().forEach { recording ->
            if (recording.isTranscribing || recording.uploadStatus == "processing") return@forEach
            if (
                RecordingRetention.isExpired(
                    dateCreatedMillis = recording.dateCreated,
                    retentionDays = retentionDays,
                    nowMillis = now,
                )
            ) {
                delete(recording)
            }
        }
    }

    private suspend fun refreshMissingDurations() {
        dao.getMissingDuration().forEach { recording ->
            val file = fileStore.fileFor(recording)
            val duration = fileStore.durationSeconds(file)
            if (duration > 0) {
                dao.update(recording.copy(duration = duration))
            }
        }
    }

    private suspend fun recoverOrphanAudioFiles() {
        val known = dao.getAll().map { it.audioFileName }.toSet()
        fileStore.listOrphanAudioFiles(known).forEach { file ->
            val created = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
            val duration = fileStore.durationSeconds(file)
            dao.insert(
                RecordingEntity(
                    title = Formatters.recoveredTitle(context, created),
                    dateCreated = created,
                    audioFileName = file.name,
                    duration = duration,
                    isImported = false,
                ),
            )
        }
    }

    /**
     * After a crash, finalize any `_pNNN.m4a` segment sets into a single recording file
     * and refresh/create the matching DB row.
     */
    private suspend fun recoverSegmentSessions(recorder: AudioRecorder?) {
        if (recorder != null &&
            (recorder.state.value != RecordingState.Idle || recorder.isFinalizing.value)
        ) {
            return
        }

        fileStore.recoverSegmentSessions().forEach { session ->
            val existing = dao.getAll().firstOrNull { it.audioFileName == session.finalFile.name }
            if (existing != null) {
                if (session.duration > 0 && kotlin.math.abs(existing.duration - session.duration) >= 0.5) {
                    dao.update(existing.copy(duration = session.duration))
                }
            } else {
                dao.insert(
                    RecordingEntity(
                        title = Formatters.recoveredTitle(context, session.createdMillis),
                        dateCreated = session.createdMillis,
                        audioFileName = session.finalFile.name,
                        duration = session.duration,
                        isImported = false,
                    ),
                )
            }
        }
    }
}
