package se.iteca.eneotaltilltext.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY dateCreated DESC")
    fun observeAll(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings ORDER BY dateCreated DESC")
    suspend fun getAll(): List<RecordingEntity>

    @Query("SELECT * FROM recordings WHERE id = :id")
    fun observeById(id: String): Flow<RecordingEntity?>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getById(id: String): RecordingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recording: RecordingEntity)

    @Update
    suspend fun update(recording: RecordingEntity)

    @Query("UPDATE recordings SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: String, title: String)

    @Query("UPDATE recordings SET uploadAppID = :appId, uploadAppName = :appName, uploadSpaceName = :spaceName WHERE id = :id")
    suspend fun updateSelectedApp(id: String, appId: String, appName: String?, spaceName: String?)

    @Query("UPDATE recordings SET uploadError = NULL, uploadStatus = NULL, uploadProgressText = NULL WHERE id = :id")
    suspend fun clearUploadError(id: String)

    @Query("""
        UPDATE recordings
        SET uploadError = NULL,
            uploadStatus = :status,
            uploadProgressText = :progress,
            uploadAppID = :appId,
            isTranscribing = 0
        WHERE id = :id
    """)
    suspend fun startUpload(id: String, status: String, progress: String?, appId: String)

    @Query("""
        UPDATE recordings
        SET uploadFileID = :fileId,
            uploadServerID = :serverId,
            uploadStatus = :status,
            uploadProgressText = NULL,
            uploadedAt = :uploadedAt,
            transcriptionText = :transcription,
            summaryText = :summary,
            title = COALESCE(:title, title),
            uploadError = NULL,
            isTranscribing = 0,
            uploadAppID = COALESCE(:appId, uploadAppID)
        WHERE id = :id
    """)
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
    )

    @Query("UPDATE recordings SET uploadServerID = NULL WHERE id = :id")
    suspend fun clearUploadRunId(id: String)

    @Query("UPDATE recordings SET uploadFileID = :fileId WHERE id = :id")
    suspend fun saveUploadFileId(id: String, fileId: String)

    @Query("UPDATE recordings SET uploadServerID = :runId WHERE id = :id")
    suspend fun saveUploadRunId(id: String, runId: String)

    @Query("""
        UPDATE recordings
        SET uploadStatus = NULL,
            uploadProgressText = NULL,
            uploadError = NULL,
            isTranscribing = 0
        WHERE id = :id
    """)
    suspend fun resetCancelledUpload(id: String)

    @Query("""
        UPDATE recordings
        SET uploadStatus = 'failed',
            uploadProgressText = NULL,
            isTranscribing = 0,
            uploadError = :error
        WHERE id = :id
    """)
    suspend fun setUploadFailed(id: String, error: String)

    @Delete
    suspend fun delete(recording: RecordingEntity)

    @Query("""
        UPDATE recordings
        SET isTranscribing = 0,
            uploadProgressText = NULL,
            uploadStatus = CASE WHEN uploadStatus = 'processing' THEN NULL ELSE uploadStatus END
        WHERE isTranscribing = 1 OR uploadStatus = 'processing'
    """)
    suspend fun resetStuckTranscribingFlags()

    @Query("SELECT * FROM recordings WHERE audioFileName = :audioFileName LIMIT 1")
    suspend fun getByAudioFileName(audioFileName: String): RecordingEntity?

    @Query("SELECT * FROM recordings WHERE duration <= 0")
    suspend fun getMissingDuration(): List<RecordingEntity>
}
