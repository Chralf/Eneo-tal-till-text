package se.iteca.eneotaltilltext.network

object RecordingMetadataParser {
    fun from(markdown: String): RecordingResultMetadata {
        val lines = markdown.replace("\r\n", "\n")
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val title = explicitValue(lines, listOf("Titel", "Rubrik")) ?: firstHeading(lines)
        val summary = explicitValue(lines, listOf("Sammanfattning", "Kort sammanfattning", "Beskrivning"))
            ?: firstParagraph(lines, title)
        return RecordingResultMetadata(
            suggestedTitle = title?.let { trimmedSentence(it, 60) },
            shortSummary = summary?.let { trimmedSentence(it, 110) },
        )
    }

    private fun explicitValue(lines: List<String>, labels: List<String>): String? {
        for (line in lines) {
            val cleaned = line.replace("**", "").replace("#", "").trim()
            for (label in labels) {
                val prefixes = listOf("$label:", "$label -", "$label –")
                val prefix = prefixes.firstOrNull { cleaned.startsWith(it, ignoreCase = true) } ?: continue
                val value = cleaned.drop(prefix.length).trim()
                if (value.isNotEmpty()) return value
            }
        }
        return null
    }

    private fun firstHeading(lines: List<String>): String? {
        return lines.firstOrNull { it.startsWith("#") }?.trim('#', ' ')
    }

    private fun firstParagraph(lines: List<String>, excluding: String?): String? {
        return lines.firstOrNull { line ->
            !line.startsWith("#") &&
                !line.startsWith("- ") &&
                !line.startsWith("* ") &&
                numberedPrefix(line) == null &&
                line != excluding
        }
    }

    private fun numberedPrefix(line: String): String? {
        val dot = line.indexOf('.')
        if (dot <= 0) return null
        line.take(dot).toIntOrNull() ?: return null
        return line.take(dot + 1)
    }

    private fun trimmedSentence(text: String, maxLength: Int): String {
        val cleaned = text.replace("**", "").trim()
        if (cleaned.length <= maxLength) return cleaned
        return cleaned.take(maxLength).trim() + "..."
    }
}
