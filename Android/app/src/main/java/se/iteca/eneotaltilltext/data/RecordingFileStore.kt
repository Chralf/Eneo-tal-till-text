package se.iteca.eneotaltilltext.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import se.iteca.eneotaltilltext.audio.M4aSegmentConcatenator
import se.iteca.eneotaltilltext.audio.RecordingSegmentNaming
import java.io.File

class RecordingFileStore(private val context: Context) {
    val recordingsDirectory: File
        get() = File(context.filesDir, "Recordings").also { it.mkdirs() }

    fun fileFor(recording: RecordingEntity): File {
        return File(recordingsDirectory, recording.audioFileName)
    }

    fun exists(recording: RecordingEntity): Boolean = fileFor(recording).exists()

    fun createRecordingFile(): File {
        return File(recordingsDirectory, RecordingFileNames.recordingFileName())
    }

    fun delete(recording: RecordingEntity) {
        fileFor(recording).delete()
    }

    fun deleteSessionPartFiles(finalFileName: String) {
        val base = RecordingSegmentNaming.baseNameFromFinalFileName(finalFileName)
        val files = recordingsDirectory.listFiles() ?: return
        files.filter { RecordingSegmentNaming.parsePart(it.name)?.first == base }
            .forEach { it.delete() }
    }

    fun durationSeconds(file: File): Double {
        if (!file.exists()) return 0.0
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val millis = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            millis / 1000.0
        } catch (_: Exception) {
            0.0
        } finally {
            retriever.release()
        }
    }

    fun importFromUri(uri: Uri): ImportedAudio {
        val originalName = queryDisplayName(uri) ?: "imported-audio"
        val extension = extensionFor(uri, originalName)
        val safeName = RecordingFileNames.sanitizeDisplayName(originalName)
        val fileName = RecordingFileNames.importFileName(safeName, extension)
        val destination = File(recordingsDirectory, fileName)
        if (destination.exists()) {
            destination.delete()
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Kunde inte läsa den delade ljudfilen.")

        if (!looksLikeSupportedAudio(destination)) {
            destination.delete()
            error("Filen ser inte ut att vara ett stött ljudformat.")
        }

        val title = originalName.substringBeforeLast('.')
            .ifBlank { "Importerad inspelning" }
        return ImportedAudio(
            title = title,
            file = destination,
            duration = durationSeconds(destination),
        )
    }

    fun listOrphanAudioFiles(knownNames: Set<String>): List<File> {
        val audioExtensions = setOf("m4a", "wav", "mp3", "caf", "aac", "flac")
        val files = recordingsDirectory.listFiles() ?: return emptyList()
        return files.filter { file ->
            audioExtensions.contains(file.extension.lowercase()) &&
                !RecordingSegmentNaming.isPartFileName(file.name) &&
                !knownNames.contains(file.name) &&
                file.length() > 1024
        }
    }

    /** Autosaved segment files for [finalFileName] that have not been merged yet. */
    fun pendingPartFiles(finalFileName: String): List<File> {
        val base = RecordingSegmentNaming.baseNameFromFinalFileName(finalFileName)
        val files = recordingsDirectory.listFiles() ?: return emptyList()
        val parts = files.filter { file ->
            RecordingSegmentNaming.parsePart(file.name)?.first == base && file.length() > 1024
        }
        return RecordingSegmentNaming.groupPartFiles(parts)[base].orEmpty()
    }

    /**
     * Merges leftover segments into `{base}.m4a` and deletes the parts only after success.
     * A failed merge leaves every part in place for a later attempt.
     */
    fun mergePendingParts(finalFileName: String): RecoveredSegmentSession? {
        val parts = pendingPartFiles(finalFileName)
        if (parts.isEmpty()) return null
        val finalFile = File(recordingsDirectory, finalFileName)
        if (!M4aSegmentConcatenator.concatenate(parts, finalFile)) return null
        deleteSessionPartFiles(finalFileName)
        val duration = durationSeconds(finalFile)
        val created = parts.minOfOrNull { it.lastModified() }?.takeIf { it > 0 }
            ?: finalFile.lastModified().takeIf { it > 0 }
            ?: System.currentTimeMillis()
        return RecoveredSegmentSession(finalFile, duration, created)
    }

    /**
     * Finds `_pNNN` segment groups, concatenates them into `{base}.m4a`, and deletes
     * parts only when the merge succeeds.
     *
     * Leftover parts next to a complete final file mean the process was killed
     * while deleting them after a successful merge. Merging again would overwrite
     * the full recording with only the parts that survived, so just finish cleanup.
     */
    fun recoverSegmentSessions(): List<RecoveredSegmentSession> {
        val files = recordingsDirectory.listFiles()?.toList().orEmpty()
        val candidateParts = files.filter {
            RecordingSegmentNaming.isPartFileName(it.name) && it.length() > 1024
        }
        return RecordingSegmentNaming.groupPartFiles(candidateParts).mapNotNull { (baseName, _) ->
            val finalFileName = RecordingSegmentNaming.finalFileName(baseName)
            val finalFile = File(recordingsDirectory, finalFileName)
            if (finalFile.exists() && finalFile.length() > 1024) {
                deleteSessionPartFiles(finalFileName)
                null
            } else {
                mergePendingParts(finalFileName)
            }
        }
    }

    private fun looksLikeSupportedAudio(file: File): Boolean {
        val header = ByteArray(12)
        val read = file.inputStream().use { it.read(header) }
        if (read < 4) return false
        fun matches(offset: Int, ascii: String): Boolean {
            if (offset + ascii.length > read) return false
            return ascii.toByteArray(Charsets.US_ASCII)
                .indices
                .all { i -> header[offset + i] == ascii[i].code.toByte() }
        }
        if (read >= 8 && matches(4, "ftyp")) return true
        if (matches(0, "RIFF") && read >= 12 && matches(8, "WAVE")) return true
        if (matches(0, "fLaC")) return true
        if (matches(0, "caff")) return true
        if (matches(0, "ID3")) return true
        if (header[0] == 0xFF.toByte() && (header[1].toInt() and 0xE0) == 0xE0) return true
        if (header[0] == 0xFF.toByte() && (header[1].toInt() and 0xF6) == 0xF0) return true
        return false
    }

    private fun queryDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index)
                }
            }
        return uri.lastPathSegment
    }

    private fun extensionFor(uri: Uri, originalName: String): String? {
        val fromName = originalName.substringAfterLast('.', "").lowercase()
        if (fromName in setOf("m4a", "wav", "mp3", "caf", "aac", "flac")) return fromName
        val mime = context.contentResolver.getType(uri) ?: return null
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
    }
}

data class ImportedAudio(
    val title: String,
    val file: File,
    val duration: Double,
)

data class RecoveredSegmentSession(
    val finalFile: File,
    val duration: Double,
    val createdMillis: Long,
)
