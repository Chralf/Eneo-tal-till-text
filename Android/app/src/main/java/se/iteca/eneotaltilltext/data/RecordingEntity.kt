package se.iteca.eneotaltilltext.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import se.iteca.eneotaltilltext.util.Formatters
import java.util.UUID

@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val dateCreated: Long = System.currentTimeMillis(),
    val duration: Double = 0.0,
    val audioFileName: String,
    val transcriptionText: String? = null,
    val isTranscribing: Boolean = false,
    val isImported: Boolean = false,
    val summaryText: String? = null,
    val uploadServerID: String? = null,
    val uploadFileID: String? = null,
    val uploadStatus: String? = null,
    val uploadProgressText: String? = null,
    val uploadedAt: Long? = null,
    val uploadError: String? = null,
    val uploadAppID: String? = null,
    val uploadAppName: String? = null,
    val uploadSpaceName: String? = null,
    /** Unused; retention is app-wide in Settings. Kept for Room schema stability. */
    val retentionDaysOverride: Int? = null,
) {
    val hasTranscription: Boolean
        get() = !transcriptionText.isNullOrBlank()

    val formattedDuration: String
        get() = Formatters.duration(duration)

    val formattedDate: String
        get() = Formatters.abbreviatedDateTime(dateCreated)

    val transcriptionState: TranscriptionState
        get() = when {
            uploadStatus == "processing" || isTranscribing -> TranscriptionState.Processing
            uploadStatus == "failed" || !uploadError.isNullOrBlank() -> TranscriptionState.Failed
            hasTranscription -> TranscriptionState.Completed
            else -> TranscriptionState.None
        }

    companion object {
        const val MAX_RECORDING_DURATION_SECONDS = 2 * 60 * 60.0
    }
}

enum class TranscriptionState {
    None,
    Processing,
    Failed,
    Completed,
}
