package se.iteca.eneotaltilltext.util

import android.content.Context
import se.iteca.eneotaltilltext.R
import java.text.DateFormat
import java.util.Date
import java.util.Locale

object Formatters {
    private val swedish = Locale.Builder().setLanguage("sv").setRegion("SE").build()

    fun duration(seconds: Double): String {
        val totalSeconds = seconds.toInt().coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val secs = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, secs)
        } else {
            "%d:%02d".format(minutes, secs)
        }
    }

    fun recordingTimer(seconds: Double): String {
        val totalSeconds = seconds.toInt().coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val secs = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, secs)
        } else {
            "%02d:%02d".format(minutes, secs)
        }
    }

    fun playbackTime(seconds: Double): String {
        if (!seconds.isFinite()) return "0:00"
        return duration(seconds)
    }

    fun abbreviatedDateTime(millis: Long): String {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, swedish)
            .format(Date(millis))
    }

    fun numericDateTime(millis: Long): String {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, swedish)
            .format(Date(millis))
    }

    fun recordingTitle(context: Context, nowMillis: Long = System.currentTimeMillis()): String {
        return context.getString(R.string.recording_title_prefix, numericDateTime(nowMillis))
    }

    fun recoveredTitle(context: Context, createdMillis: Long): String {
        return context.getString(R.string.recovered_title_prefix, numericDateTime(createdMillis))
    }

    fun isPlaceholderTitle(title: String): Boolean {
        val trimmed = title.trim()
        return trimmed.startsWith("Inspelning ") || trimmed.startsWith("Återställd ")
    }
}
