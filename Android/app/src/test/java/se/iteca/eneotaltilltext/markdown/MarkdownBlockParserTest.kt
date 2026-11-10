package se.iteca.eneotaltilltext.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownBlockParserTest {
    @Test
    fun headings_parseAtEveryLevelAndStripClosingHashes() {
        val blocks = MarkdownBlockParser.parse("# Rubrik\n### Under ##\n####### Inte rubrik")
        assertEquals(3, blocks.size)
        assertEquals(MarkdownBlockKind.Heading(1), blocks[0].kind)
        assertEquals("Rubrik", blocks[0].text)
        assertEquals(MarkdownBlockKind.Heading(3), blocks[1].kind)
        assertEquals("Under", blocks[1].text)
        assertEquals(MarkdownBlockKind.Paragraph, blocks[2].kind)
    }

    @Test
    fun paragraph_joinsSoftLineBreaksAndStopsAtBlankLine() {
        val blocks = MarkdownBlockParser.parse("Första raden\nandra raden\n\nNytt stycke")
        assertEquals(2, blocks.size)
        assertEquals(MarkdownBlockKind.Paragraph, blocks[0].kind)
        assertEquals("Första raden andra raden", blocks[0].text)
        assertEquals("Nytt stycke", blocks[1].text)
    }

    @Test
    fun bulletLists_keepDepthFromIndentation() {
        val blocks = MarkdownBlockParser.parse("- Ett\n  - Nästlad\n- Två")
        assertEquals(3, blocks.size)
        assertEquals(MarkdownBlockKind.Bullet(0), blocks[0].kind)
        assertEquals("Ett", blocks[0].text)
        assertEquals(MarkdownBlockKind.Bullet(1), blocks[1].kind)
        assertEquals("Nästlad", blocks[1].text)
        assertEquals(MarkdownBlockKind.Bullet(0), blocks[2].kind)
    }

    @Test
    fun numberedLists_keepTheirNumbersAndContinuationLines() {
        val blocks = MarkdownBlockParser.parse("1. Första\n   fortsättning\n2) Andra")
        assertEquals(2, blocks.size)
        assertEquals(MarkdownBlockKind.Numbered(0, 1), blocks[0].kind)
        assertEquals("Första\nfortsättning", blocks[0].text)
        assertEquals(MarkdownBlockKind.Numbered(0, 2), blocks[1].kind)
        assertEquals("Andra", blocks[1].text)
    }

    @Test
    fun looseList_survivesBlankLineBetweenItems() {
        val blocks = MarkdownBlockParser.parse("- Ett\n\n- Två\n\nStycke")
        assertEquals(3, blocks.size)
        assertTrue(blocks[0].kind is MarkdownBlockKind.Bullet)
        assertTrue(blocks[1].kind is MarkdownBlockKind.Bullet)
        assertEquals(MarkdownBlockKind.Paragraph, blocks[2].kind)
        assertEquals("Stycke", blocks[2].text)
    }

    @Test
    fun fencedCodeBlock_keepsContentVerbatim() {
        val blocks = MarkdownBlockParser.parse("```swift\nlet a = 1\n  let b = 2\n```\nEfter")
        assertEquals(2, blocks.size)
        assertEquals(MarkdownBlockKind.Code("swift"), blocks[0].kind)
        assertEquals("let a = 1\n  let b = 2", blocks[0].text)
        assertEquals(MarkdownBlockKind.Paragraph, blocks[1].kind)
        assertEquals("Efter", blocks[1].text)
    }

    @Test
    fun quoteLines_areMergedIntoOneBlock() {
        val blocks = MarkdownBlockParser.parse("> Första\n> Andra\nStycke")
        assertEquals(2, blocks.size)
        assertEquals(MarkdownBlockKind.Quote, blocks[0].kind)
        assertEquals("Första\nAndra", blocks[0].text)
        assertEquals(MarkdownBlockKind.Paragraph, blocks[1].kind)
    }

    @Test
    fun table_requiresSeparatorRow() {
        val withSeparator = MarkdownBlockParser.parse("| Namn | Roll |\n|---|:---:|\n| Anna | Ordförande |")
        val withoutSeparator = MarkdownBlockParser.parse("| Namn | Roll |\n| Anna | Ordförande |")
        assertEquals(1, withSeparator.size)
        assertEquals(
            MarkdownBlockKind.Table(listOf("Namn", "Roll"), listOf(listOf("Anna", "Ordförande"))),
            withSeparator[0].kind,
        )
        assertEquals(1, withoutSeparator.size)
        assertEquals(MarkdownBlockKind.Paragraph, withoutSeparator[0].kind)
    }

    @Test
    fun thematicBreak_isRecognizedWithSpaces() {
        val blocks = MarkdownBlockParser.parse("Före\n\n- - -\n\nEfter")
        assertEquals(3, blocks.size)
        assertEquals(MarkdownBlockKind.ThematicBreak, blocks[1].kind)
    }

    @Test
    fun windowsLineEndings_areNormalized() {
        val blocks = MarkdownBlockParser.parse("# Rubrik\r\nText\r\n")
        assertEquals(2, blocks.size)
        assertEquals(MarkdownBlockKind.Heading(1), blocks[0].kind)
        assertEquals(MarkdownBlockKind.Paragraph, blocks[1].kind)
        assertEquals("Text", blocks[1].text)
    }

    @Test
    fun parse_headingParagraphAndList() {
        val blocks = MarkdownBlockParser.parse(
            """
            # Titel
            En mening.

            - punkt ett
            - punkt två
            """.trimIndent(),
        )
        assertTrue(blocks[0].kind is MarkdownBlockKind.Heading)
        assertEquals("Titel", blocks[0].text)
        assertEquals(MarkdownBlockKind.Paragraph, blocks[1].kind)
        assertEquals("En mening.", blocks[1].text)
        assertTrue(blocks[2].kind is MarkdownBlockKind.Bullet)
        assertTrue(blocks[3].kind is MarkdownBlockKind.Bullet)
    }

    @Test
    fun parse_tableWithoutOuterPipes() {
        val blocks = MarkdownBlockParser.parse(
            """
            A | B | C
            ---|---|---
            1 | 2 | 3
            """.trimIndent(),
        )
        val table = blocks.single().kind as MarkdownBlockKind.Table
        assertEquals(listOf("A", "B", "C"), table.header)
        assertEquals(listOf(listOf("1", "2", "3")), table.rows)
    }

    @Test
    fun parse_pipeInOrdinaryParagraph_makesProgress() {
        val blocks = MarkdownBlockParser.parse("A | B | C")
        assertEquals(1, blocks.size)
        assertEquals(MarkdownBlockKind.Paragraph, blocks.single().kind)
        assertEquals("A | B | C", blocks.single().text)
    }
}
