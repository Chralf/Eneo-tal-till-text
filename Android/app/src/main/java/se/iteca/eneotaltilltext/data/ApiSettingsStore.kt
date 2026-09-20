package se.iteca.eneotaltilltext.data

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ApiSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val secureStore = KeystoreStringStore(appContext)
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        clearLegacyApiKeys()
    }

    var baseUrl: String
        get() {
            val stored = prefs.getString(KEY_BASE_URL, null)?.trim().orEmpty()
            val raw = stored.ifEmpty { DEFAULT_BASE_URL }
            return normalizedBaseUrl(raw).ifEmpty { DEFAULT_BASE_URL }
        }
        set(value) {
            val normalized = normalizedBaseUrl(value)
            if (makeBaseUrl(normalized) != null) {
                prefs.edit().putString(KEY_BASE_URL, normalized).apply()
            }
        }

    var appId: String
        get() {
            val stored = prefs.getString(KEY_APP_ID, null)?.trim().orEmpty()
            return stored.ifEmpty { DEFAULT_APP_ID }
        }
        set(value) {
            prefs.edit().putString(KEY_APP_ID, value.trim().ifEmpty { DEFAULT_APP_ID }).apply()
        }

    var defaultAppName: String
        get() = prefs.getString(KEY_APP_NAME, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_APP_NAME, value).apply()
        }

    var defaultAppSpaceName: String
        get() = prefs.getString(KEY_APP_SPACE, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_APP_SPACE, value).apply()
        }

    var accessToken: String
        get() = secureStore.get(KEY_ACCESS_TOKEN)
        set(value) {
            if (value.isBlank()) {
                secureStore.remove(KEY_ACCESS_TOKEN)
            } else {
                secureStore.put(KEY_ACCESS_TOKEN, value.trim())
            }
            clearLegacyApiKeys()
        }

    val hasAccessToken: Boolean
        get() = accessToken.isNotBlank()

    var pendingAuthState: String?
        get() = prefs.getString(KEY_PENDING_STATE, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_PENDING_STATE) else putString(KEY_PENDING_STATE, value)
            }.apply()
        }

    /** Positive days (1...30). Default is 30. */
    var retentionDays: Int
        get() {
            if (!prefs.contains(RecordingRetention.STORAGE_KEY)) return RecordingRetention.DEFAULT_DAYS
            return RecordingRetention.clamped(
                prefs.getInt(RecordingRetention.STORAGE_KEY, RecordingRetention.DEFAULT_DAYS),
            )
        }
        set(value) {
            prefs.edit().putInt(RecordingRetention.STORAGE_KEY, RecordingRetention.clamped(value)).apply()
        }

    var onboardingCompleted: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, value).apply()
        }

    val hasExplicitServerUrl: Boolean
        get() {
            val stored = prefs.getString(KEY_BASE_URL, null)?.trim().orEmpty()
            return stored.isNotEmpty()
        }

    fun shouldShowOnboarding(): Boolean = !onboardingCompleted && !hasExplicitServerUrl

    fun migrateExistingUsersIfNeeded() {
        if (hasAccessToken || hasExplicitServerUrl) {
            onboardingCompleted = true
        }
    }

    fun callbackHost(): String? = makeBaseUrl(baseUrl)?.let { Uri.parse(it).host }

    fun storedDefaultAppDisplayName(): String? {
        val name = defaultAppName.trim()
        if (name.isEmpty()) return null
        val space = defaultAppSpaceName.trim()
        return if (space.isEmpty()) name else "[$space] $name"
    }

    fun persistDefaultAppDisplayName(name: String, spaceName: String, appId: String) {
        if (appId != this.appId) return
        defaultAppName = name
        defaultAppSpaceName = spaceName
    }

    fun loadSettings(selectedAppId: String? = null): ApiSettings {
        val url = makeBaseUrl(baseUrl) ?: throw IllegalStateException("invalid_base_url")
        val trimmedSelected = selectedAppId?.trim().orEmpty()
        val resolvedAppId = trimmedSelected.ifEmpty { appId }.ifEmpty { DEFAULT_APP_ID }
        val token = accessToken
        if (token.isEmpty()) throw IllegalStateException("missing_token")
        return ApiSettings(baseUrl = url, appId = resolvedAppId, accessToken = token)
    }

    private fun clearLegacyApiKeys() {
        secureStore.remove("apiKey")
        appContext.getSharedPreferences("eneo_secure_prefs_fallback", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        appContext.deleteSharedPreferences("eneo_secure_prefs")
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://eneo.kommun.se"
        const val DEFAULT_APP_ID = "3412dabc-7343-49bb-b3bc-9f99204d10b2"

        private const val PREFS_NAME = "eneo_settings"
        private const val KEY_BASE_URL = "eneoBaseURLString"
        private const val KEY_APP_ID = "eneoAppID"
        private const val KEY_APP_NAME = "eneoAppName"
        private const val KEY_APP_SPACE = "eneoAppSpaceName"
        private const val KEY_ACCESS_TOKEN = "accessToken"
        private const val KEY_PENDING_STATE = "eneoPendingAuthState"
        private const val KEY_ONBOARDING_COMPLETED = "onboardingCompleted"

        fun normalizedBaseUrl(value: String): String {
            var trimmed = value.trim()
            if (trimmed.isEmpty()) return ""
            if (!trimmed.contains("://")) trimmed = "https://$trimmed"
            return trimmed.trimEnd('/')
        }

        fun makeBaseUrl(value: String): String? {
            val normalized = normalizedBaseUrl(value)
            if (normalized.isEmpty()) return null
            val uri = runCatching { Uri.parse(normalized) }.getOrNull() ?: return null
            if (!uri.scheme.equals("https", ignoreCase = true)) return null
            if (uri.host.isNullOrBlank()) return null
            return normalized
        }
    }
}

private class KeystoreStringStore(context: Context) {
    private val prefs = context.getSharedPreferences("eneo_secure_prefs_v2", Context.MODE_PRIVATE)

    fun get(key: String): String {
        val packed = prefs.getString(key, null) ?: return ""
        return decrypt(packed).orEmpty()
    }

    fun put(key: String, value: String) {
        prefs.edit().putString(key, encrypt(value)).apply()
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val packed = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, packed, 0, iv.size)
        System.arraycopy(encrypted, 0, packed, iv.size, encrypted.size)
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(packed: String): String? {
        return try {
            val data = Base64.decode(packed, Base64.NO_WRAP)
            if (data.size <= GCM_IV_LENGTH) return null
            val iv = data.copyOfRange(0, GCM_IV_LENGTH)
            val encrypted = data.copyOfRange(GCM_IV_LENGTH, data.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "eneo_api_key_aes"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
    }
}

data class ApiSettings(
    val baseUrl: String,
    val appId: String,
    val accessToken: String,
)
