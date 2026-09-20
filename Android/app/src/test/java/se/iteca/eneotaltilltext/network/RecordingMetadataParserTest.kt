package se.iteca.eneotaltilltext.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingMetadataParserTest {
    @Test
    fun from_prefersExplicitTitleAndSummaryLabels() {
        val metadata = RecordingMetadataParser.from(
            """
            Titel: Statusmöte
            Sammanfattning: Kort genomgång av sprinten.
            Mer text som inte ska bli titel.
            """.trimIndent(),
        )
        assertEquals("Statusmöte", metadata.suggestedTitle)
        assertEquals("Kort genomgång av sprinten.", metadata.shortSummary)
    }

    @Test
    fun from_fallsBackToHeadingAndFirstParagraph() {
        val metadata = RecordingMetadataParser.from(
            """
            # Rubrik från heading
            Detta är ingressen som sammanfattar innehållet.
            """.trimIndent(),
        )
        assertEquals("Rubrik från heading", metadata.suggestedTitle)
        assertEquals("Detta är ingressen som sammanfattar innehållet.", metadata.shortSummary)
    }

    @Test
    fun from_truncatesLongValues() {
        val longTitle = "T".repeat(80)
        val longSummary = "S".repeat(150)
        val metadata = RecordingMetadataParser.from(
            """
            Titel: $longTitle
            Sammanfattning: $longSummary
            """.trimIndent(),
        )
        assertTrue(metadata.suggestedTitle!!.endsWith("..."))
        assertEquals(63, metadata.suggestedTitle!!.length)
        assertTrue(metadata.shortSummary!!.endsWith("..."))
        assertEquals(113, metadata.shortSummary!!.length)
    }

    @Test
    fun from_emptyMarkdownReturnsNulls() {
        val metadata = RecordingMetadataParser.from("\n\n")
        assertNull(metadata.suggestedTitle)
        assertNull(metadata.shortSummary)
    }
}
