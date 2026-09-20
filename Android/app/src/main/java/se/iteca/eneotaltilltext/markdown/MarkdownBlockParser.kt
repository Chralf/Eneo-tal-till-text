package se.iteca.eneotaltilltext.markdown

private fun looksLikeTableRow(line: String): Boolean {
    val trimmed = line.trim()
    return trimmed.contains('|') && trimmed.count { it == '|' } >= 2
}

private fun isTableSeparator(line: String): Boolean {
    val trimmed = line.trim().trim('|').trim()
    if (trimmed.isEmpty()) return false
    return trimmed.split('|').all { cell ->
        val content = cell.trim()
        content.isNotEmpty() && content.all { it == '-' || it == ':' || it == ' ' }
    }
}

private fun parseTableCells(line: String): List<String> {
    var trimmed = line.trim()
    if (trimmed.startsWith("|")) trimmed = trimmed.drop(1)
    if (trimmed.endsWith("|")) trimmed = trimmed.dropLast(1)
    return trimmed.split('|').map { it.trim() }
}

private fun consumeTable(lines: List<String>, start: Int): Pair<MarkdownBlock, Int> {
    val header = parseTableCells(lines[start])
    var index = start + 2
    val rows = mutableListOf<List<String>>()
    while (index < lines.size) {
        val trimmed = lines[index].trim()
        if (trimmed.isEmpty()) break
        if (!looksLikeTableRow(trimmed) || isTableSeparator(trimmed)) break
        rows += parseTableCells(lines[index])
        index++
    }
    return MarkdownBlock(MarkdownBlockKind.Table(header, rows), "") to index
}

internal data class MarkdownBlock(
    val kind: MarkdownBlockKind,
    val text: String,
)

internal sealed class MarkdownBlockKind {
    data class Heading(val level: Int) : MarkdownBlockKind()
    data object Paragraph : MarkdownBlockKind()
    data class Bullet(val depth: Int) : MarkdownBlockKind()
    data class Numbered(val depth: Int, val number: Int) : MarkdownBlockKind()
    data object Quote : MarkdownBlockKind()
    data class Code(val language: String?) : MarkdownBlockKind()
    data class Table(val header: List<String>, val rows: List<List<String>>) : MarkdownBlockKind()
    data object ThematicBreak : MarkdownBlockKind()
}

internal object MarkdownBlockParser {
    fun parse(markdown: String): List<MarkdownBlock> {
        val lines = markdown.replace("\r\n", "\n").replace("\r", "\n").split('\n')
        val blocks = mutableListOf<MarkdownBlock>()
        var index = 0
        while (index < lines.size) {
            val rawLine = lines[index]
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) {
                index++
                continue
            }
            val fence = matchFence(trimmed)
            if (fence != null) {
                val (block, next) = consumeCodeBlock(lines, index, fence)
                blocks += block
                index = next
                continue
            }
            if (isThematicBreak(trimmed)) {
                blocks += MarkdownBlock(MarkdownBlockKind.ThematicBreak, "")
                index++
                continue
            }
            val heading = heading(trimmed)
            if (heading != null) {
                blocks += heading
                index++
                continue
            }
            if (trimmed.startsWith(">")) {
                val (block, next) = consumeQuote(lines, index)
                blocks += block
                index = next
                continue
            }
            val listStart = matchListItem(rawLine)
            if (listStart != null) {
                val (listBlocks, next) = consumeList(lines, index, listStart)
                blocks += listBlocks
                index = next
                continue
            }
            if (looksLikeTableRow(trimmed) && index + 1 < lines.size && isTableSeparator(lines[index + 1].trim())) {
                val (block, next) = consumeTable(lines, index)
                blocks += block
                index = next
                continue
            }
            val (paragraph, next) = consumeParagraph(lines, index)
            blocks += paragraph
            index = next
        }
        return blocks
    }

    private fun heading(line: String): MarkdownBlock? {
        for (level in 6 downTo 1) {
            val prefix = "#".repeat(level) + " "
            if (!line.startsWith(prefix)) continue
            val cleaned = stripTrailingAtxHashes(line.drop(prefix.length).trim())
            if (cleaned.isEmpty()) return null
            return MarkdownBlock(MarkdownBlockKind.Heading(level), cleaned)
        }
        return null
    }

    private fun matchFence(line: String): String? {
        if (line.startsWith("```") || line.startsWith("~~~")) return line.take(3)
        return null
    }

    private fun consumeCodeBlock(lines: List<String>, start: Int, opening: String): Pair<MarkdownBlock, Int> {
        val openingLine = lines[start].trim()
        val language = openingLine.drop(3).trim().ifEmpty { null }
        val body = mutableListOf<String>()
        var index = start + 1
        while (index < lines.size) {
            val trimmed = lines[index].trim()
            if (trimmed.startsWith(opening)) {
                index++
                break
            }
            body += lines[index]
            index++
        }
        if (body.lastOrNull()?.trim().isNullOrEmpty() && body.isNotEmpty()) {
            body.removeAt(body.lastIndex)
        }
        return MarkdownBlock(MarkdownBlockKind.Code(language), body.joinToString("\n")) to index
    }

    private fun consumeQuote(lines: List<String>, start: Int): Pair<MarkdownBlock, Int> {
        val parts = mutableListOf<String>()
        var index = start
        while (index < lines.size) {
            val trimmed = lines[index].trim()
            if (!trimmed.startsWith(">")) break
            var content = trimmed.drop(1)
            if (content.startsWith(" ")) content = content.drop(1)
            if (content.isNotEmpty()) parts += content
            index++
        }
        return MarkdownBlock(MarkdownBlockKind.Quote, parts.joinToString("\n")) to index
    }

    private data class ListMatch(
        val indent: Int,
        val depth: Int,
        val kind: ListKind,
        val text: String,
    )

    private sealed class ListKind {
        data object Bullet : ListKind()
        data class Numbered(val number: Int) : ListKind()
    }

    private fun matchListItem(rawLine: String): ListMatch? {
        val indent = leadingWhitespaceCount(rawLine)
        val content = rawLine.drop(indent)
        if (content.startsWith("- ") || content.startsWith("* ") || content.startsWith("+ ")) {
            return ListMatch(indent, depth(indent), ListKind.Bullet, content.drop(2))
        }
        val numbered = matchOrderedMarker(content) ?: return null
        return ListMatch(indent, depth(indent), ListKind.Numbered(numbered.first), numbered.second)
    }

    private fun matchOrderedMarker(content: String): Pair<Int, String>? {
        var count = 0
        while (count < content.length && content[count].isDigit()) {
            count++
            if (count > 9) return null
        }
        if (count == 0 || count >= content.length) return null
        val marker = content[count]
        if (marker != '.' && marker != ')') return null
        if (count + 1 >= content.length || content[count + 1] != ' ') return null
        val number = content.take(count).toIntOrNull() ?: return null
        return number to content.substring(count + 2)
    }

    private fun consumeList(lines: List<String>, start: Int, first: ListMatch): Pair<List<MarkdownBlock>, Int> {
        val blocks = mutableListOf<MarkdownBlock>()
        var index = start
        var current = first
        var textLines = mutableListOf(current.text)
        val baseIndent = first.indent

        fun flush() {
            val text = textLines.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            blocks += when (val kind = current.kind) {
                ListKind.Bullet -> MarkdownBlock(MarkdownBlockKind.Bullet(current.depth), text)
                is ListKind.Numbered -> MarkdownBlock(MarkdownBlockKind.Numbered(current.depth, kind.number), text)
            }
            textLines = mutableListOf()
        }

        index++
        while (index < lines.size) {
            val rawLine = lines[index]
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) {
                val nextIndex = indexOfNextNonEmpty(lines, index)
                val nextItem = nextIndex?.let { matchListItem(lines[it]) }
                if (nextItem != null && nextItem.indent >= baseIndent) {
                    index++
                    continue
                }
                break
            }
            val nextItem = matchListItem(rawLine)
            if (nextItem != null && nextItem.indent >= baseIndent) {
                flush()
                current = nextItem
                textLines = mutableListOf(nextItem.text)
                index++
                continue
            }
            val indent = leadingWhitespaceCount(rawLine)
            if (indent > current.indent && matchFence(trimmed) == null && heading(trimmed) == null) {
                textLines += rawLine.drop(indent)
                index++
                continue
            }
            break
        }
        flush()
        return blocks to index
    }

    private fun depth(indent: Int) = maxOf(0, indent / 2)

    private fun consumeParagraph(lines: List<String>, start: Int): Pair<MarkdownBlock, Int> {
        val parts = mutableListOf<String>()
        var index = start
        while (index < lines.size) {
            val rawLine = lines[index]
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) break
            if (matchFence(trimmed) != null) break
            if (isThematicBreak(trimmed)) break
            if (heading(trimmed) != null) break
            if (trimmed.startsWith(">")) break
            if (matchListItem(rawLine) != null) break
            // A pipe in ordinary prose is not a table unless the next line is
            // a Markdown separator. Consume the line so parsing always advances.
            if (looksLikeTableRow(trimmed) &&
                index + 1 < lines.size &&
                isTableSeparator(lines[index + 1].trim())
            ) break
            parts += trimmed
            index++
        }
        return MarkdownBlock(MarkdownBlockKind.Paragraph, parts.joinToString(" ")) to index
    }

    private fun stripTrailingAtxHashes(text: String): String {
        val regex = Regex("""\s+#+$""")
        val match = regex.find(text) ?: return text
        return text.substring(0, match.range.first).trim()
    }

    private fun isThematicBreak(line: String): Boolean {
        val compact = line.replace(" ", "")
        if (compact.length < 3) return false
        return compact.all { it == '-' } || compact.all { it == '*' } || compact.all { it == '_' }
    }

    private fun leadingWhitespaceCount(line: String): Int {
        var count = 0
        for (character in line) {
            count += when (character) {
                ' ' -> 1
                '\t' -> 4
                else -> return count
            }
        }
        return count
    }

    private fun indexOfNextNonEmpty(lines: List<String>, after: Int): Int? {
        var next = after + 1
        while (next < lines.size) {
            if (lines[next].trim().isNotEmpty()) return next
            next++
        }
        return null
    }
}
