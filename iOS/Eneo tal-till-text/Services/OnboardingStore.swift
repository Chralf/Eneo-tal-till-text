import Foundation

enum OnboardingStore {
    static let completedKey = "onboardingCompleted"
    /// Set once the one-time migration below has run, so an onboarding that
    /// was abandoned after the server step is not mistaken for a finished one
    /// on the next launch.
    static let migrationCheckedKey = "onboardingMigrationChecked"

    static var shouldShowOnboarding: Bool {
        !UserDefaults.standard.bool(forKey: completedKey)
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

    static var storedServerURLString: String {
        (UserDefaults.standard.string(forKey: APISettings.baseURLStorageKey) ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func markCompleted() {
        UserDefaults.standard.set(true, forKey: completedKey)
    }

    /// Users who installed a version without onboarding already have a server
    /// address or a token. Treat them as done, but only on the first launch
    /// after the update; later launches must not re-run this check.
    static func migrateExistingUsersIfNeeded() {
        let defaults = UserDefaults.standard
        guard !defaults.bool(forKey: migrationCheckedKey) else { return }
        defaults.set(true, forKey: migrationCheckedKey)

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
