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
            # Mötesanteckningar
            **Titel:** Budgetmöte oktober
            Sammanfattning: Gruppen gick igenom utfallet.
            - Punkt
            """.trimIndent(),
        )
        assertEquals("Budgetmöte oktober", metadata.suggestedTitle)
        assertEquals("Gruppen gick igenom utfallet.", metadata.shortSummary)
    }

    @Test
    fun from_fallsBackToHeadingAndFirstParagraph() {
        val metadata = RecordingMetadataParser.from(
            """
            ## Planeringsmöte

            - Första punkten
            1. Numrerad punkt

            Detta är det första riktiga stycket.
            """.trimIndent(),
        )
        assertEquals("Planeringsmöte", metadata.suggestedTitle)
        assertEquals("Detta är det första riktiga stycket.", metadata.shortSummary)
    }

    @Test
    fun from_summaryDoesNotRepeatTheTitle() {
        val metadata = RecordingMetadataParser.from("Bara en rad\nBara en rad\nAndra raden")
        assertNull(metadata.suggestedTitle)
        assertEquals("Bara en rad", metadata.shortSummary)
    }

    @Test
    fun from_truncatesLongValues() {
        val longTitle = "a".repeat(80)
        val metadata = RecordingMetadataParser.from("# $longTitle")
        assertTrue(metadata.suggestedTitle!!.endsWith("..."))
        assertEquals(63, metadata.suggestedTitle!!.length)
    }

    @Test
    fun from_truncatesLongSummary() {
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
        val metadata = RecordingMetadataParser.from("\n\n   \n")
        assertNull(metadata.suggestedTitle)
        assertNull(metadata.shortSummary)
    }
}
