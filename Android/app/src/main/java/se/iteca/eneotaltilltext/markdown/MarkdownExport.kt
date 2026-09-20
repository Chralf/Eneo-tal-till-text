package se.iteca.eneotaltilltext.markdown

/**
 * Converts app markdown into formats that paste well into mail and office apps.
 * HTML keeps headings/lists/bold; plain text is readable without markdown markers.
 */
object MarkdownExport {
    data class Payload(
        val html: String,
        val plain: String,
    )

    fun fromMarkdown(markdown: String): Payload {
        val blocks = MarkdownBlockParser.parse(markdown)
        return Payload(
            html = toHtml(blocks),
            plain = toPlain(blocks),
        )
    }

    private fun toHtml(blocks: List<MarkdownBlock>): String {
        if (blocks.isEmpty()) return ""
        val body = buildString {
            for (block in blocks) {
                when (val kind = block.kind) {
                    is MarkdownBlockKind.Heading -> {
                        val level = kind.level.coerceIn(1, 6)
                        append("<h$level>")
                        append(inlineHtml(block.text))
                        append("</h$level>\n")
                    }
                    MarkdownBlockKind.Paragraph -> {
                        append("<p>")
                        append(inlineHtml(block.text))
                        append("</p>\n")
                    }
                    is MarkdownBlockKind.Bullet -> {
                        val pad = "&nbsp;".repeat(kind.depth * 4)
                        append("<p>$pad• ")
                        append(inlineHtml(block.text).replace("\n", "<br>"))
                        append("</p>\n")
                    }
                    is MarkdownBlockKind.Numbered -> {
                        val pad = "&nbsp;".repeat(kind.depth * 4)
                        append("<p>$pad${kind.number}. ")
                        append(inlineHtml(block.text).replace("\n", "<br>"))
                        append("</p>\n")
                    }
                    MarkdownBlockKind.Quote -> {
                        append("<blockquote>")
                        append(inlineHtml(block.text).replace("\n", "<br>"))
                        append("</blockquote>\n")
                    }
                    is MarkdownBlockKind.Code -> {
                        append("<pre><code>")
                        append(escapeHtml(block.text))
                        append("</code></pre>\n")
                    }
                    is MarkdownBlockKind.Table -> {
                        append("<table>")
                        append("<thead><tr>")
                        kind.header.forEach { append("<th>").append(inlineHtml(it)).append("</th>") }
                        append("</tr></thead><tbody>")
                        kind.rows.forEach { row ->
                            append("<tr>")
                            row.forEach { cell -> append("<td>").append(inlineHtml(cell)).append("</td>") }
                            append("</tr>")
                        }
                        append("</tbody></table>\n")
                    }
                    MarkdownBlockKind.ThematicBreak -> append("<hr>\n")
                }
            }
        }
        return """
            <!DOCTYPE html>
            <html><body>
            $body
            </body></html>
        """.trimIndent()
    }

    private fun toPlain(blocks: List<MarkdownBlock>): String {
        if (blocks.isEmpty()) return ""
        return buildString {
            for ((index, block) in blocks.withIndex()) {
                if (index > 0) append("\n\n")
                when (val kind = block.kind) {
                    is MarkdownBlockKind.Heading -> append(stripInlineMarkers(block.text))
                    MarkdownBlockKind.Paragraph -> append(stripInlineMarkers(block.text))
                    is MarkdownBlockKind.Bullet -> {
                        append("  ".repeat(kind.depth))
                        append("• ")
                        append(stripInlineMarkers(block.text).replace("\n", "\n  ".repeat(kind.depth) + "  "))
                    }
                    is MarkdownBlockKind.Numbered -> {
                        append("  ".repeat(kind.depth))
                        append("${kind.number}. ")
                        append(stripInlineMarkers(block.text))
                    }
                    MarkdownBlockKind.Quote -> {
                        stripInlineMarkers(block.text).lineSequence().forEachIndexed { i, line ->
                            if (i > 0) append('\n')
                            append("> ").append(line)
                        }
                    }
                    is MarkdownBlockKind.Code -> append(block.text)
                    is MarkdownBlockKind.Table -> {
                        append(kind.header.joinToString(" | ") { stripInlineMarkers(it) })
                        append('\n')
                        append(kind.header.joinToString(" | ") { "---" })
                        kind.rows.forEach { row ->
                            append('\n')
                            append(row.joinToString(" | ") { stripInlineMarkers(it) })
                        }
                    }
                    MarkdownBlockKind.ThematicBreak -> append("—")
                }
            }
        }.trim()
    }

    private fun inlineHtml(text: String): String {
        // Order matters: bold before italic so ** is not eaten by *.
        var result = escapeHtml(text)
        result = Regex("""\*\*(.+?)\*\*""").replace(result) { "<strong>${it.groupValues[1]}</strong>" }
        result = Regex("""`([^`]+)`""").replace(result) { "<code>${it.groupValues[1]}</code>" }
        result = Regex("""\*(.+?)\*""").replace(result) { "<em>${it.groupValues[1]}</em>" }
        return result
    }

    private fun stripInlineMarkers(text: String): String {
        var result = text
        result = Regex("""\*\*(.+?)\*\*""").replace(result) { it.groupValues[1] }
        result = Regex("""`([^`]+)`""").replace(result) { it.groupValues[1] }
        result = Regex("""\*(.+?)\*""").replace(result) { it.groupValues[1] }
        return result
    }

    private fun escapeHtml(text: String): String = buildString(text.length) {
        for (ch in text) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(ch)
            }
        }
    }
}
