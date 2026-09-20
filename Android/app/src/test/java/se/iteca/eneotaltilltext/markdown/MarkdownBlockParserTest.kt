package se.iteca.eneotaltilltext.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownBlockParserTest {
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
    fun parse_tableWithHeaderAndRows() {
        val blocks = MarkdownBlockParser.parse(
            """
            | Namn | Roll |
            | --- | --- |
            | Ada | Dev |
            | Bob | QA |
            """.trimIndent(),
        )
        assertEquals(1, blocks.size)
        val table = blocks[0].kind as MarkdownBlockKind.Table
        assertEquals(listOf("Namn", "Roll"), table.header)
        assertEquals(listOf(listOf("Ada", "Dev"), listOf("Bob", "QA")), table.rows)
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
    fun parse_codeFenceAndQuote() {
        val blocks = MarkdownBlockParser.parse(
            """
            > citerat

            ```kotlin
            val x = 1
            ```
            """.trimIndent(),
        )
        assertEquals(MarkdownBlockKind.Quote, blocks[0].kind)
        assertEquals("citerat", blocks[0].text)
        val code = blocks[1].kind as MarkdownBlockKind.Code
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1", blocks[1].text)
    }

    @Test
    fun parse_numberedListPreservesOrder() {
        val blocks = MarkdownBlockParser.parse(
            """
            1. första
            2. andra
            """.trimIndent(),
        )
        val first = blocks[0].kind as MarkdownBlockKind.Numbered
        val second = blocks[1].kind as MarkdownBlockKind.Numbered
        assertEquals(1, first.number)
        assertEquals(2, second.number)
    }

    @Test
    fun parse_pipeInOrdinaryParagraph_makesProgress() {
        val blocks = MarkdownBlockParser.parse("A | B | C")
        assertEquals(1, blocks.size)
        assertEquals(MarkdownBlockKind.Paragraph, blocks.single().kind)
        assertEquals("A | B | C", blocks.single().text)
    }
}
