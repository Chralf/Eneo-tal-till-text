package se.iteca.eneotaltilltext.data

import java.util.UUID

object RecordingFileNames {
    fun recordingFileName(
        epochSeconds: Long = System.currentTimeMillis() / 1000,
        uuidPrefix: String = UUID.randomUUID().toString().take(8),
    ): String = "recording_${epochSeconds}_${uuidPrefix}.m4a"

    fun importFileName(
        safeName: String,
        extension: String?,
        epochSeconds: Long = System.currentTimeMillis() / 1000,
        uuidPrefix: String = UUID.randomUUID().toString().take(8),
    ): String {
        val base = "shared_${epochSeconds}_${uuidPrefix}_$safeName"
        return if (extension != null && !base.lowercase().endsWith(".$extension")) {
            "$base.$extension"
        } else {
            base
        }
    }

    fun sanitizeDisplayName(fileName: String): String {
        val sanitized = buildString {
            fileName.forEach { ch ->
                append(if (ch.isLetterOrDigit() || ch in "._- ") ch else '_')
            }
        }
        return sanitized.ifBlank { "imported-audio" }
    }
}
