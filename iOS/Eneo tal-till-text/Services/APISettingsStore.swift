import Foundation
import Security

struct AuthTokenStore {
    private static let tokenService = "se.iteca.EneoTalTillText.auth"
    private static let tokenAccount = "accessToken"
    private static let pendingStateKey = "eneoPendingAuthState"
    private static let accountEmailAccount = "accountEmail"

    private static let legacyAPIKeyService = "se.iteca.EneoTalTillText.api"
    private static let legacyItecaAPIKeyService = "se.iteca.ItecaWhisper.api"
    private static let legacyAPIKeyAccount = "apiPassword"

    static var hasAccessToken: Bool {
        !loadAccessToken().trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    static func loadAccessToken() -> String {
        loadValue(service: tokenService, account: tokenAccount) ?? ""
    }

    static func saveAccessToken(_ accessToken: String) {
        saveValue(accessToken, service: tokenService, account: tokenAccount)
        clearLegacyAPIKeys()
    }

    static func clearAccessToken() {
        saveValue("", service: tokenService, account: tokenAccount)
        clearLegacyAPIKeys()
    }

    static func savePendingState(_ state: String) {
        UserDefaults.standard.set(state, forKey: pendingStateKey)
    }

    static func loadPendingState() -> String? {
        UserDefaults.standard.string(forKey: pendingStateKey)
    }

    static func clearPendingState() {
        UserDefaults.standard.removeObject(forKey: pendingStateKey)
    }

    /// The signed-in account's e-mail, kept so "Inloggad som …" can be shown
    /// offline after a relaunch. Personal data, so it lives beside the token in
    /// the device-only Keychain rather than in backed-up UserDefaults.
    static func loadAccountEmail() -> String? {
        let value = loadValue(service: tokenService, account: accountEmailAccount)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return value.isEmpty ? nil : value
    }

    static func saveAccountEmail(_ email: String?) {
        let trimmed = email?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        saveValue(trimmed, service: tokenService, account: accountEmailAccount)
    }

    static func clearAccountEmail() {
        saveValue("", service: tokenService, account: accountEmailAccount)
    }

    static func clearLegacyAPIKeys() {
        SecItemDelete(query(service: legacyAPIKeyService, account: legacyAPIKeyAccount) as CFDictionary)
        SecItemDelete(query(service: legacyItecaAPIKeyService, account: legacyAPIKeyAccount) as CFDictionary)
    }

    private static func loadValue(service: String, account: String) -> String? {
        var query = query(service: service, account: account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess,
              let data = item as? Data,
              let value = String(data: data, encoding: .utf8)
        else {
            return nil
        }
        return value
    }

    private static func saveValue(_ value: String, service: String, account: String) {
        let query = query(service: service, account: account)

        if value.isEmpty {
            SecItemDelete(query as CFDictionary)
            return
        }

        let data = Data(value.utf8)
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            // Background upload needs the token after the first unlock while
            // the device is locked. It remains device-bound and non-migrating.
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]

        let status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            var newItem = query
            newItem[kSecValueData as String] = data
            newItem[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            SecItemAdd(newItem as CFDictionary, nil)
        }
    }

    private static func query(service: String, account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
    }
}
