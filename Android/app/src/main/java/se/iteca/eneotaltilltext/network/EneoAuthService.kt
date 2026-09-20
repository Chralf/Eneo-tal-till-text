package se.iteca.eneotaltilltext.network

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import se.iteca.eneotaltilltext.R
import se.iteca.eneotaltilltext.data.ApiSettingsStore
import se.iteca.eneotaltilltext.data.ErrorPresentation
import se.iteca.eneotaltilltext.data.PresentableException
import java.util.concurrent.TimeUnit

class EneoAuthService(
    private val context: Context,
    private val settingsStore: ApiSettingsStore,
) {
    private val _isAuthenticated = MutableStateFlow(settingsStore.hasAccessToken)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private val _isSigningIn = MutableStateFlow(false)
    val isSigningIn: StateFlow<Boolean> = _isSigningIn.asStateFlow()

    private val _statusText = MutableStateFlow<String?>(null)
    val statusText: StateFlow<String?> = _statusText.asStateFlow()

    private val _error = MutableStateFlow<ErrorPresentation?>(null)
    val error: StateFlow<ErrorPresentation?> = _error.asStateFlow()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun refreshAuthenticationState() {
        if (!settingsStore.hasAccessToken) {
            _isAuthenticated.value = false
            return
        }
        try {
            verifySession()
            _isAuthenticated.value = true
        } catch (error: PresentableException) {
            if (error.presentation.requiresSignIn) {
                signOut()
            } else {
                _isAuthenticated.value = true
            }
        } catch (_: Exception) {
            _isAuthenticated.value = true
        }
    }

    fun handleExpiredSession() {
        settingsStore.accessToken = ""
        settingsStore.pendingAuthState = null
        _isAuthenticated.value = false
    }

    suspend fun beginSignIn(): Uri {
        if (_isSigningIn.value) {
            throw PresentableException(
                ErrorPresentation(
                    title = context.getString(R.string.auth_already_in_progress_title),
                    message = context.getString(R.string.auth_already_in_progress_message),
                    requiresSignIn = false,
                ),
            )
        }
        _isSigningIn.value = true
        _error.value = null
        _statusText.value = context.getString(R.string.login_opening)
        try {
            val (url, state) = initiateAuthentication()
            settingsStore.pendingAuthState = state
            _statusText.value = context.getString(R.string.login_waiting)
            return url
        } catch (error: Exception) {
            _isSigningIn.value = false
            _statusText.value = null
            val presentation = ErrorPresentation.from(context, error)
            _error.value = presentation
            throw if (error is PresentableException) error else PresentableException(presentation)
        }
    }

    suspend fun completeSignIn(callbackUrl: Uri) {
        try {
            val code = callbackUrl.getQueryParameter("code").orEmpty()
            val state = callbackUrl.getQueryParameter("state").orEmpty()
            if (code.isEmpty() || state.isEmpty()) {
                throw PresentableException(
                    ErrorPresentation(
                        title = context.getString(R.string.auth_invalid_callback_title),
                        message = context.getString(R.string.auth_try_again),
                        requiresSignIn = true,
                    ),
                )
            }
            if (state != settingsStore.pendingAuthState) {
                throw PresentableException(
                    ErrorPresentation(
                        title = context.getString(R.string.auth_state_mismatch_title),
                        message = context.getString(R.string.auth_try_again),
                        requiresSignIn = true,
                    ),
                )
            }
            _statusText.value = context.getString(R.string.login_finishing)
            val token = exchangeCodeForToken(code, state)
            settingsStore.accessToken = token
            settingsStore.pendingAuthState = null
            _statusText.value = context.getString(R.string.login_verifying)
            verifySession()
            _isAuthenticated.value = true
            _statusText.value = null
            _error.value = null
        } catch (error: Exception) {
            settingsStore.accessToken = ""
            settingsStore.pendingAuthState = null
            _isAuthenticated.value = false
            _statusText.value = null
            _error.value = ErrorPresentation.from(context, error)
        }
        _isSigningIn.value = false
    }

    fun cancelSignIn() {
        settingsStore.pendingAuthState = null
        _isSigningIn.value = false
        _statusText.value = null
    }

    fun clearError() {
        _error.value = null
    }

    fun signOut() {
        settingsStore.accessToken = ""
        settingsStore.pendingAuthState = null
        _isAuthenticated.value = false
        _isSigningIn.value = false
        _statusText.value = null
        _error.value = null
    }

    suspend fun verifySession() = withContext(Dispatchers.IO) {
        val token = settingsStore.accessToken
        if (token.isEmpty()) {
            throw PresentableException(
                ErrorPresentation(
                    title = context.getString(R.string.auth_missing_token_title),
                    message = context.getString(R.string.auth_missing_token_message),
                    requiresSignIn = true,
                ),
            )
        }
        val url = makeUrl("/api/v1/users/me/")
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $token")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code !in 200..299) {
                throw PresentableException(
                    ErrorPresentation(
                        title = context.getString(R.string.auth_verification_failed_title),
                        message = context.getString(R.string.auth_verification_failed_message),
                        technicalDetails = context.getString(R.string.auth_verification_details, response.code),
                        requiresSignIn = response.code == 401 || response.code == 403,
                    ),
                )
            }
        }
    }

    private suspend fun initiateAuthentication(): Pair<Uri, String> = withContext(Dispatchers.IO) {
        val url = makeUrl("/api/v1/auth/initiate")
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            validate(response.code, body, context.getString(R.string.debug_initiate_login), url)
            val json = JSONObject(body)
            val authUrl = json.optString("authorization_url")
            val state = json.optString("state")
            val uri = Uri.parse(authUrl)
            if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
                throw PresentableException(
                    ErrorPresentation(
                        title = context.getString(R.string.auth_untrusted_url_title),
                        message = context.getString(R.string.auth_untrusted_url_message),
                    ),
                )
            }
            if (state.isBlank()) {
                throw PresentableException(
                    ErrorPresentation(
                        title = context.getString(R.string.auth_invalid_response_title),
                        message = context.getString(R.string.auth_invalid_response_message),
                    ),
                )
            }
            uri to state
        }
    }

    private suspend fun exchangeCodeForToken(code: String, state: String): String = withContext(Dispatchers.IO) {
        val url = makeUrl("/api/v1/auth/callback")
        val payload = JSONObject().put("code", code).put("state", state).toString()
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            validate(response.code, body, context.getString(R.string.debug_complete_login), url)
            val token = JSONObject(body).optString("access_token").trim()
            if (token.isEmpty()) {
                throw PresentableException(
                    ErrorPresentation(
                        title = context.getString(R.string.auth_invalid_response_title),
                        message = context.getString(R.string.auth_invalid_response_message),
                    ),
                )
            }
            token
        }
    }

    private fun makeUrl(path: String): String {
        val base = ApiSettingsStore.makeBaseUrl(settingsStore.baseUrl)
            ?: throw PresentableException(
                ErrorPresentation(
                    title = context.getString(R.string.auth_invalid_url_title),
                    message = context.getString(R.string.auth_invalid_url_message),
                ),
            )
        val appended = path.trim().trim('/')
        val slash = if (path.endsWith("/")) "/" else ""
        return "$base/$appended$slash"
    }

    private fun validate(code: Int, body: String, operation: String, url: String) {
        if (code in 200..299) return
        val message = body.takeIf { it.isNotBlank() }
        throw PresentableException(
            ErrorPresentation(
                title = context.getString(R.string.auth_server_error_title),
                message = message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.auth_server_error_message),
                technicalDetails = if (message.isNullOrBlank()) {
                    context.getString(R.string.auth_server_details, operation, code, url)
                } else {
                    context.getString(R.string.auth_server_details_body, operation, code, url, message)
                },
                requiresSignIn = code == 401 || code == 403,
            ),
        )
    }

    companion object {
        fun isLoginCallback(url: Uri, callbackHost: String): Boolean {
            if (!url.scheme.equals("https", ignoreCase = true)) return false
            if (!url.host.equals(callbackHost, ignoreCase = true)) return false
            val path = url.path.orEmpty().trim('/')
            if (path != "login/callback") return false
            return !url.getQueryParameter("code").isNullOrBlank() &&
                !url.getQueryParameter("state").isNullOrBlank()
        }
    }
}
