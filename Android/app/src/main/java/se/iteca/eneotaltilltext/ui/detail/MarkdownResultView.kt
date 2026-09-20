package se.iteca.eneotaltilltext.ui.detail

import se.iteca.eneotaltilltext.markdown.MarkdownBlock
import se.iteca.eneotaltilltext.markdown.MarkdownBlockKind
import se.iteca.eneotaltilltext.markdown.MarkdownBlockParser

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MarkdownResultView(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { MarkdownBlockParser.parse(markdown) }
    SelectionContainer {
        Column(modifier.fillMaxWidth()) {
            blocks.forEachIndexed { index, block ->
                val previous = blocks.getOrNull(index - 1)
                Box(Modifier.padding(top = topPadding(block, previous).dp)) {
                    MarkdownBlockView(block)
                }
            }
        }
    }
}

@Composable
private fun MarkdownBlockView(block: MarkdownBlock) {
    when (val kind = block.kind) {
        is MarkdownBlockKind.Heading -> {
            val style = when (kind.level) {
                1 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                2 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                3 -> MaterialTheme.typography.titleMedium
                4 -> MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                else -> MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
            }
            Text(text = inlineMarkdown(block.text), style = style)
        }
        MarkdownBlockKind.Paragraph -> {
            Text(
                text = inlineMarkdown(block.text),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        is MarkdownBlockKind.Bullet -> {
            ListRow(marker = "•", depth = kind.depth, text = block.text)
        }
        is MarkdownBlockKind.Numbered -> {
            ListRow(marker = "${kind.number}.", depth = kind.depth, text = block.text)
        }
        MarkdownBlockKind.Quote -> {
            Row(
                modifier = Modifier.height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .padding(vertical = 2.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                Text(
                    text = inlineMarkdown(block.text),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        is MarkdownBlockKind.Code -> {
            Text(
                text = block.text,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(12.dp),
            )
        }
        is MarkdownBlockKind.Table -> {
            MarkdownTableView(header = kind.header, rows = kind.rows)
        }
        MarkdownBlockKind.ThematicBreak -> {
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        }
    }
}

@Composable
private fun MarkdownTableView(header: List<String>, rows: List<List<String>>) {
    val columnCount = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
    val normalizedHeader = header.padCells(columnCount)
    val normalizedRows = rows.map { it.padCells(columnCount) }
    val scrollState = rememberScrollState()
    val cellStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = (MaterialTheme.typography.bodyLarge.fontSize.value * 0.875f).sp,
        lineHeight = (MaterialTheme.typography.bodyLarge.lineHeight.value * 0.875f).sp,
    )
    val headerStyle = cellStyle.copy(fontWeight = FontWeight.SemiBold)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
    ) {
        Row {
            normalizedHeader.forEachIndexed { index, cell ->
                TableCell(
                    text = cell,
                    style = headerStyle,
                    isHeader = true,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (index < columnCount - 1) {
                    TableVerticalDivider()
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        normalizedRows.forEachIndexed { rowIndex, row ->
            Row {
                row.forEachIndexed { index, cell ->
                    TableCell(
                        text = cell,
                        style = cellStyle,
                        isHeader = false,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (index < columnCount - 1) {
                        TableVerticalDivider()
                    }
                }
            }
            if (rowIndex < normalizedRows.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun TableCell(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    isHeader: Boolean,
    modifier: Modifier = Modifier,
) {
    Text(
        text = inlineMarkdown(text),
        style = style,
        fontFamily = if (isHeader) FontFamily.Default else FontFamily.Monospace,
        textAlign = TextAlign.Start,
        modifier = modifier
            .widthIn(min = 72.dp)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    )
}

@Composable
private fun TableVerticalDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(IntrinsicSize.Min)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

private fun List<String>.padCells(count: Int): List<String> {
    if (size >= count) return take(count)
    return this + List(count - size) { "" }
}

@Composable
private fun ListRow(marker: String, depth: Int, text: String) {
    Row(
        modifier = Modifier.padding(start = (depth * 18).dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            marker,
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            inlineMarkdown(text),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun topPadding(block: MarkdownBlock, previous: MarkdownBlock?): Int {
    if (previous == null) return 0
    return when (block.kind) {
        is MarkdownBlockKind.Heading -> if (block.kind.level <= 2) 18 else 14
        MarkdownBlockKind.ThematicBreak -> 12
        is MarkdownBlockKind.Bullet, is MarkdownBlockKind.Numbered -> {
            when (previous.kind) {
                is MarkdownBlockKind.Bullet, is MarkdownBlockKind.Numbered -> 4
                else -> 10
            }
        }
        MarkdownBlockKind.Quote, is MarkdownBlockKind.Code, is MarkdownBlockKind.Table -> 12
        MarkdownBlockKind.Paragraph -> 10
    }
}

private fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < text.length) {
        when {
            text.startsWith("**", index) -> {
                val end = text.indexOf("**", index + 2)
                if (end >= 0) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(text.substring(index + 2, end))
                    pop()
                    index = end + 2
                } else {
                    append(text[index])
                    index++
                }
            }
            text.startsWith("`", index) && !text.startsWith("```", index) -> {
                val end = text.indexOf('`', index + 1)
                if (end >= 0) {
                    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace))
                    append(text.substring(index + 1, end))
                    pop()
                    index = end + 1
                } else {
                    append(text[index])
                    index++
                }
            }
            text.startsWith("*", index) && !text.startsWith("**", index) -> {
                val end = text.indexOf("*", index + 1)
                if (end >= 0) {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(text.substring(index + 1, end))
                    pop()
                    index = end + 1
                } else {
                    append(text[index])
                    index++
                }
            }
            else -> {
                append(text[index])
                index++
            }
        }
    }
}

