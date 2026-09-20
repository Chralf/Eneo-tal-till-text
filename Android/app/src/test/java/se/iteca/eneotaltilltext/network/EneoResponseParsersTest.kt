package se.iteca.eneotaltilltext.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EneoResponseParsersTest {
    @Test
    fun statusHelpers_recognizeTerminalStates() {
        assertTrue(EneoResponseParsers.isCompletedStatus("completed"))
        assertTrue(EneoResponseParsers.isCompletedStatus("success"))
        assertTrue(EneoResponseParsers.isFailedStatus("failed"))
        assertTrue(EneoResponseParsers.isFailedStatus("canceled"))
        assertFalse(EneoResponseParsers.isCompletedStatus("running"))
        assertFalse(EneoResponseParsers.isFailedStatus("queued"))
    }

    @Test
    fun makeUrl_joinsBasePathAndQuery() {
        val url = EneoResponseParsers.makeUrl(
            "https://example.com/api/",
            "/v1/spaces/",
            mapOf("include_personal" to "true"),
        )
        assertEquals("https://example.com/api/v1/spaces/?include_personal=true", url)
    }

    @Test
    fun parseSpaces_supportsWrappedItemsAndSingleObject() {
        val list = EneoResponseParsers.parseSpaces(
            """{"items":[{"id":"s1","name":"Alpha"},{"id":"s2","title":"Beta"}]}""",
        )
        assertEquals(listOf("s1" to "Alpha", "s2" to "Beta"), list.map { it.id to it.name })

        val single = EneoResponseParsers.parseSpaces("""{"id":"personal","display_name":"Mig"}""")
        assertEquals(listOf("personal" to "Mig"), single.map { it.id to it.name })
    }

    @Test
    fun parseApps_readsNestedAppsItems() {
        val space = EneoSpace("space-1", "Rum")
        val apps = EneoResponseParsers.parseApps(
            """{"apps":{"items":[{"id":"a1","name":"Tal"},{"id":"a2","title":"Anteckning"}]}}""",
            space,
        )
        assertEquals(2, apps.size)
        assertEquals("a1", apps[0].id)
        assertEquals("space-1", apps[0].spaceId)
        assertEquals("[Rum] Anteckning", apps[1].displayName)
    }

    @Test
    fun acceptsAudioInput_detectsUploadAndRecorderFields() {
        assertTrue(
            EneoResponseParsers.acceptsAudioInput(
                """{"input_fields":[{"type":"text"},{"type":"audio-upload"}]}""",
            ),
        )
        assertTrue(
            EneoResponseParsers.acceptsAudioInput(
                """{"input_fields":[{"type":"audio-recorder"}]}""",
            ),
        )
        assertFalse(
            EneoResponseParsers.acceptsAudioInput(
                """{"input_fields":[{"type":"text"}]}""",
            ),
        )
    }

    @Test
    fun parseRunResult_readsOutputAndFailure() {
        val ok = EneoResponseParsers.parseRunResult(
            """{"id":"r1","status":"completed","created_at":"2024-01-01","output":"# Klar"}""",
        )
        assertEquals("r1", ok.id)
        assertEquals("completed", ok.status)
        assertEquals("# Klar", ok.output)
        assertTrue(ok.hasOutput)

        val failed = EneoResponseParsers.parseRunResult(
            """{"status":"failed","error":"timeout"}""",
        )
        assertEquals("timeout", failed.failureMessage)
    }
}
