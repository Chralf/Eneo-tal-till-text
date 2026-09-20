package se.iteca.eneotaltilltext.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingRetentionTest {
    @Test
    fun clamped_mapsNonPositiveToDefault() {
        assertEquals(RecordingRetention.DEFAULT_DAYS, RecordingRetention.clamped(0))
        assertEquals(RecordingRetention.DEFAULT_DAYS, RecordingRetention.clamped(-3))
    }

    @Test
    fun clamped_capsAtMaxDays() {
        assertEquals(RecordingRetention.MAX_DAYS, RecordingRetention.clamped(999))
        assertEquals(15, RecordingRetention.clamped(15))
    }

    @Test
    fun isExpired_usesWholeDaysFromCreation() {
        val created = 1_700_000_000_000L
        val dayMs = 24L * 60L * 60L * 1000L
        assertFalse(RecordingRetention.isExpired(created, 7, created + dayMs * 6))
        assertTrue(RecordingRetention.isExpired(created, 7, created + dayMs * 7))
    }

    @Test
    fun isExpired_futureCreationIsNotExpired() {
        val now = 1_700_000_000_000L
        assertFalse(RecordingRetention.isExpired(now + 60_000L, 1, now))
    }

    @Test
    fun label_usesSwedishSingularAndPlural() {
        assertEquals("1 dag", RecordingRetention.label(1))
        assertEquals("7 dagar", RecordingRetention.label(7))
    }
}
