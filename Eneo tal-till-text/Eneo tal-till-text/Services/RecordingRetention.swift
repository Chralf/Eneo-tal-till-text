import Foundation

enum RecordingRetention {
    static let storageKey = "recordingRetentionDays"
    static let maxDays = 30
    static let defaultDays = 30
    static let selectableDays: [Int] = Array(1...maxDays)

    static var appDefaultDays: Int {
        get {
            guard UserDefaults.standard.object(forKey: storageKey) != nil else {
                return defaultDays
            }
            return clamped(UserDefaults.standard.integer(forKey: storageKey))
        }
        set {
            UserDefaults.standard.set(clamped(newValue), forKey: storageKey)
        }
    }

    /// Always 1...maxDays. Values ≤0 become defaultDays.
    static func clamped(_ days: Int) -> Int {
        if days < 1 { return defaultDays }
        return min(maxDays, days)
    }

    static func isExpired(
        dateCreated: Date,
        retentionDays: Int = RecordingRetention.appDefaultDays,
        now: Date = Date()
    ) -> Bool {
        let days = clamped(retentionDays)
        let age = now.timeIntervalSince(dateCreated)
        guard age.isFinite, age >= 0 else { return false }
        return age >= TimeInterval(days) * 24 * 60 * 60
    }

    static func label(forDays days: Int) -> String {
        let value = clamped(days)
        if value == 1 {
            return String(localized: "1 dag", comment: "Gallringsval: en dag")
        }
        return String(
            localized: "\(value) dagar",
            comment: "Gallringsval i antal dagar. Platshållaren är antalet."
        )
    }
}
