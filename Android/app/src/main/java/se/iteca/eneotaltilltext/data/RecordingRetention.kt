package se.iteca.eneotaltilltext.data

object RecordingRetention {
    const val STORAGE_KEY = "recordingRetentionDays"
    const val MAX_DAYS = 30
    const val DEFAULT_DAYS = 30
    val SELECTABLE_DAYS: List<Int> = (1..MAX_DAYS).toList()

    /** Always 1...MAX_DAYS. Values ≤0 become DEFAULT_DAYS. */
    fun clamped(days: Int): Int =
        if (days < 1) DEFAULT_DAYS else days.coerceAtMost(MAX_DAYS)

    fun isExpired(
        dateCreatedMillis: Long,
        retentionDays: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val days = clamped(retentionDays)
        val age = nowMillis - dateCreatedMillis
        if (age < 0) return false
        return age >= days.toLong() * 24L * 60L * 60L * 1000L
    }

    fun label(days: Int): String {
        val value = clamped(days)
        return if (value == 1) "1 dag" else "$value dagar"
    }
}
