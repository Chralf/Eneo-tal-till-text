import Foundation
import UserNotifications

struct TranscriptionNotificationService {
    static func requestAuthorizationIfNeeded() async {
        let center = UNUserNotificationCenter.current()
        let settings = await notificationSettings(center: center)

        guard settings.authorizationStatus == .notDetermined else { return }
        _ = try? await requestAuthorization(center: center)
    }

    static func notifyCompleted() async {
        await addNotification(
            title: String(localized: "Texten är klar", comment: "Notisrubrik när omvandlingen lyckades"),
            body: String(localized: "En inspelning är omvandlad till text och finns i appen.", comment: "Notistext utan titel, så inget känsligt syns på låsskärmen"),
            identifierPrefix: "transcription-completed"
        )
    }

    /// Titles and error details stay in the app: a notification can be read by
    /// anyone glancing at the Lock Screen.
    static func notifyFailed() async {
        await addNotification(
            title: String(localized: "Omvandlingen misslyckades", comment: "Notisrubrik när omvandlingen misslyckades"),
            body: String(localized: "En omvandling kunde inte slutföras. Öppna appen för att se varför.", comment: "Notistext utan titel eller feldetaljer"),
            identifierPrefix: "transcription-failed"
        )
    }

    private static func addNotification(title: String, body: String, identifierPrefix: String) async {
        let center = UNUserNotificationCenter.current()
        let settings = await notificationSettings(center: center)
        guard [.authorized, .provisional, .ephemeral].contains(settings.authorizationStatus) else { return }

        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default

        let request = UNNotificationRequest(
            identifier: "\(identifierPrefix)-\(UUID().uuidString)",
            content: content,
            trigger: nil
        )
        try? await add(request: request, center: center)
    }

    private static func notificationSettings(center: UNUserNotificationCenter) async -> UNNotificationSettings {
        await withCheckedContinuation { continuation in
            center.getNotificationSettings { settings in
                continuation.resume(returning: settings)
            }
        }
    }

    private static func requestAuthorization(center: UNUserNotificationCenter) async throws -> Bool {
        try await withCheckedThrowingContinuation { continuation in
            center.requestAuthorization(options: [.alert, .sound, .badge]) { granted, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: granted)
                }
            }
        }
    }

    private static func add(request: UNNotificationRequest, center: UNUserNotificationCenter) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            center.add(request) { error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: ())
                }
            }
        }
    }
}
