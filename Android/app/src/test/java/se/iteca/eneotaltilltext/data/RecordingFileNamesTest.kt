package se.iteca.eneotaltilltext.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingFileNamesTest {
    @Test
    fun recordingFileName_includesEpochUuidAndExtension() {
        val name = RecordingFileNames.recordingFileName(epochSeconds = 1_700_000_000L, uuidPrefix = "abcd1234")
        assertEquals("recording_1700000000_abcd1234.m4a", name)
    }

    @Test
    fun recordingFileName_isUniqueAcrossCalls() {
        val first = RecordingFileNames.recordingFileName()
        val second = RecordingFileNames.recordingFileName()
        assertNotEquals(first, second)
    }

    @Test
    fun importFileName_prefixesSharedAndKeepsSafeName() {
        val name = RecordingFileNames.importFileName(
            safeName = "meeting notes.m4a",
            extension = "m4a",
            epochSeconds = 1_700_000_000L,
            uuidPrefix = "deadbeef",
        )
        assertEquals("shared_1700000000_deadbeef_meeting notes.m4a", name)
    }

    @Test
    fun importFileName_appendsExtensionWhenMissing() {
        val name = RecordingFileNames.importFileName(
            safeName = "clip",
            extension = "wav",
            epochSeconds = 10L,
            uuidPrefix = "ffffffff",
        )
        assertEquals("shared_10_ffffffff_clip.wav", name)
    }

    @Test
    fun importFileName_collisionRiskRemovedByUuid() {
        val a = RecordingFileNames.importFileName("voice.m4a", "m4a", epochSeconds = 42L, uuidPrefix = "11111111")
        val b = RecordingFileNames.importFileName("voice.m4a", "m4a", epochSeconds = 42L, uuidPrefix = "22222222")
        assertNotEquals(a, b)
        assertTrue(a.contains("11111111"))
        assertTrue(b.contains("22222222"))
    }

    @Test
    fun sanitizeDisplayName_replacesUnsafeCharacters() {
        assertEquals("a_b_c.m4a", RecordingFileNames.sanitizeDisplayName("a/b\\c.m4a"))
        assertEquals("___", RecordingFileNames.sanitizeDisplayName("???"))
        assertEquals("imported-audio", RecordingFileNames.sanitizeDisplayName(""))
    }
}
