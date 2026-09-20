package se.iteca.eneotaltilltext.audio

import java.io.File

/**
 * Naming for kill-safe recording segments:
 * - final: `recording_<epoch>.m4a`
 * - parts: `recording_<epoch>_p001.m4a`, `_p002.m4a`, …
 */
object RecordingSegmentNaming {
    private val partPattern = Regex("""^(.+)_p(\d{3})\.m4a$""", RegexOption.IGNORE_CASE)

    fun baseNameFromFinalFileName(fileName: String): String =
        fileName.removeSuffix(".m4a").removeSuffix(".M4A")

    fun finalFileName(baseName: String): String = "$baseName.m4a"

    fun partFileName(baseName: String, index: Int): String =
        "%s_p%03d.m4a".format(baseName, index)

    fun partFile(directory: File, baseName: String, index: Int): File =
        File(directory, partFileName(baseName, index))

    fun finalFile(directory: File, baseName: String): File =
        File(directory, finalFileName(baseName))

    fun isPartFileName(fileName: String): Boolean = partPattern.matches(fileName)

    fun parsePart(fileName: String): Pair<String, Int>? {
        val match = partPattern.matchEntire(fileName) ?: return null
        val base = match.groupValues[1]
        val index = match.groupValues[2].toIntOrNull() ?: return null
        return base to index
    }

    /** Groups orphan part files by session base name, sorted by part index. */
    fun groupPartFiles(files: List<File>): Map<String, List<File>> {
        val grouped = mutableMapOf<String, MutableList<Pair<Int, File>>>()
        for (file in files) {
            val parsed = parsePart(file.name) ?: continue
            grouped.getOrPut(parsed.first) { mutableListOf() }.add(parsed.second to file)
        }
        return grouped.mapValues { (_, parts) ->
            parts.sortedBy { it.first }.map { it.second }
        }
    }
}
