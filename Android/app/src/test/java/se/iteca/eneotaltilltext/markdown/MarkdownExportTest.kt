package se.iteca.eneotaltilltext.markdown

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownExportTest {
    @Test
    fun fromMarkdown_exportsHtmlHeadingsListsAndBold() {
        val payload = MarkdownExport.fromMarkdown(
            """
            # Titel
            En **viktig** mening.

            - punkt ett
            - punkt två
            """.trimIndent(),
        )
        assertTrue(payload.html.contains("<h1>"))
        assertTrue(payload.html.contains("<strong>viktig</strong>"))
        assertTrue(payload.html.contains("•"))
        assertTrue(payload.plain.contains("Titel"))
        assertTrue(payload.plain.contains("viktig"))
        assertFalse(payload.plain.contains("**"))
        assertFalse(payload.plain.contains("# "))
    }
}
