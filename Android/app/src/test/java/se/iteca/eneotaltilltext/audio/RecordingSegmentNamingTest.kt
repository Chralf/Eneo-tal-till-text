package se.iteca.eneotaltilltext.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecordingSegmentNamingTest {
    @Test
    fun partFileNames_areZeroPaddedAndRoundTrip() {
        val name = RecordingSegmentNaming.partFileName("recording_1700000000", 7)
        assertEquals("recording_1700000000_p007.m4a", name)
        assertEquals("recording_1700000000" to 7, RecordingSegmentNaming.parsePart(name))
    }

    @Test
    fun finalFileNames_areNotMistakenForParts() {
        assertFalse(RecordingSegmentNaming.isPartFileName("recording_1700000000.m4a"))
        assertFalse(RecordingSegmentNaming.isPartFileName("recording_p001.wav"))
        assertFalse(RecordingSegmentNaming.isPartFileName("recording_1700000000_p1.m4a"))
        assertTrue(RecordingSegmentNaming.isPartFileName("Recording_1700000000_P002.M4A"))
    }

    @Test
    fun baseName_stripsOnlyTheM4aExtension() {
        assertEquals("recording_1", RecordingSegmentNaming.baseNameFromFinalFileName("recording_1.m4a"))
        assertEquals("shared_x_möte.wav", RecordingSegmentNaming.baseNameFromFinalFileName("shared_x_möte.wav"))
        assertEquals("recording_1.m4a", RecordingSegmentNaming.finalFileName("recording_1"))
    }

    @Test
    fun partAndFinalNames_roundTrip() {
        val base = "recording_1700000000_abcd1234"
        assertEquals("$base.m4a", RecordingSegmentNaming.finalFileName(base))
        assertEquals("${base}_p001.m4a", RecordingSegmentNaming.partFileName(base, 1))
        assertEquals("${base}_p012.m4a", RecordingSegmentNaming.partFileName(base, 12))
        assertEquals(base, RecordingSegmentNaming.baseNameFromFinalFileName("$base.m4a"))
    }

    @Test
    fun parsePart_readsBaseAndIndex() {
        assertEquals("session" to 3, RecordingSegmentNaming.parsePart("session_p003.m4a"))
        assertNull(RecordingSegmentNaming.parsePart("session.m4a"))
        assertFalse(RecordingSegmentNaming.isPartFileName("session.m4a"))
        assertTrue(RecordingSegmentNaming.isPartFileName("session_p001.m4a"))
    }

    @Test
    fun groupPartFiles_sortsByIndexWithinEachSession() {
        val files = listOf(
            File("/tmp/a_p010.m4a"),
            File("/tmp/b_p002.m4a"),
            File("/tmp/a_p002.m4a"),
            File("/tmp/a.m4a"),
            File("/tmp/a_p001.m4a"),
        )
        val grouped = RecordingSegmentNaming.groupPartFiles(files)
        assertEquals(listOf("a", "b"), grouped.keys.sorted())
        assertEquals(listOf("a_p001.m4a", "a_p002.m4a", "a_p010.m4a"), grouped.getValue("a").map { it.name })
        assertEquals(listOf("b_p002.m4a"), grouped.getValue("b").map { it.name })
    }
}
