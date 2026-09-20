import Foundation

enum OnboardingStore {
    static let completedKey = "onboardingCompleted"

    static var shouldShowOnboarding: Bool {
        if UserDefaults.standard.bool(forKey: completedKey) {
            return false
        }
        if hasExplicitServerURL {
            return false
        }
        return true
    }

    static var hasExplicitServerURL: Bool {
        guard let stored = UserDefaults.standard.string(forKey: APISettings.baseURLStorageKey)?
            .trimmingCharacters(in: .whitespacesAndNewlines),
            !stored.isEmpty
        else {
            return false
        }
        return true
    }

    static func markCompleted() {
        UserDefaults.standard.set(true, forKey: completedKey)
    }

    static func migrateExistingUsersIfNeeded() {
        if AuthTokenStore.hasAccessToken || hasExplicitServerURL {
            markCompleted()
        }
    }

    static func saveServerURL(_ value: String) {
        let normalized = APISettings.normalizedBaseURLString(value)
        guard APISettings.makeBaseURL(from: normalized) != nil else { return }
        UserDefaults.standard.set(normalized, forKey: APISettings.baseURLStorageKey)
    }
}
