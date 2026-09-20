import Foundation

/// Locale-aware elapsed-time formatting. A hard-coded `"%d:%02d"` assumes a
/// colon separator and Western digits, which is not true in every locale.
enum DurationFormatting {
    /// Counter style: `4:07` under an hour, `1:04:07` above it.
    static func elapsed(_ time: TimeInterval) -> String {
        let seconds = time.isFinite ? max(0, Int(time)) : 0
        let duration = Duration.seconds(seconds)

        if seconds >= 3600 {
            return duration.formatted(.time(pattern: .hourMinuteSecond(padHourToLength: 1)))
        }
        return duration.formatted(.time(pattern: .minuteSecond(padMinuteToLength: 1)))
    }

    /// Timestamp style with tenths, used for transcript segments: `04:07.3`.
    static func timestamp(_ time: TimeInterval) -> String {
        let seconds = time.isFinite ? max(0, time) : 0
        return Duration.seconds(seconds)
            .formatted(.time(pattern: .minuteSecond(padMinuteToLength: 2, fractionalSecondsLength: 1)))
    }
}
