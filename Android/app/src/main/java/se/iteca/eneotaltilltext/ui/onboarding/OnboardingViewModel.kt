package se.iteca.eneotaltilltext.ui.onboarding

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import se.iteca.eneotaltilltext.data.ApiSettingsStore
import se.iteca.eneotaltilltext.data.ErrorPresentation
import se.iteca.eneotaltilltext.network.EneoApp
import se.iteca.eneotaltilltext.network.EneoAuthService
import se.iteca.eneotaltilltext.network.RecordingUploadService
import se.iteca.eneotaltilltext.ui.eneoViewModelFactory

enum class OnboardingStep {
    Welcome,
    ServerUrl,
    VerifyServer,
    Login,
    SelectApp,
}

enum class VerifyPhase {
    Idle,
    Running,
    Success,
    Failure,
}

enum class LoginPhase {
    Prompt,
    SigningIn,
    SignedIn,
}

class OnboardingViewModel(
    private val app: Application,
    private val settingsStore: ApiSettingsStore,
    private val uploadService: RecordingUploadService,
    val authService: EneoAuthService,
) : ViewModel() {
    private val _step = MutableStateFlow(OnboardingStep.Welcome)
    val step: StateFlow<OnboardingStep> = _step.asStateFlow()

    private val _serverUrlDraft = MutableStateFlow(
        ApiSettingsStore.displayBaseUrl(settingsStore.baseUrl),
    )
    val serverUrlDraft: StateFlow<String> = _serverUrlDraft.asStateFlow()

    private val _verifyPhase = MutableStateFlow(VerifyPhase.Idle)
    val verifyPhase: StateFlow<VerifyPhase> = _verifyPhase.asStateFlow()

    private val _verifiedHost = MutableStateFlow<String?>(null)
    val verifiedHost: StateFlow<String?> = _verifiedHost.asStateFlow()

    private val _verifyErrorMessage = MutableStateFlow<String?>(null)
    val verifyErrorMessage: StateFlow<String?> = _verifyErrorMessage.asStateFlow()

    private val _loginPhase = MutableStateFlow(LoginPhase.Prompt)
    val loginPhase: StateFlow<LoginPhase> = _loginPhase.asStateFlow()

    private val _authorizationUrl = MutableStateFlow<Uri?>(null)
    val authorizationUrl: StateFlow<Uri?> = _authorizationUrl.asStateFlow()

    private val _callbackHost = MutableStateFlow<String?>(null)
    val callbackHost: StateFlow<String?> = _callbackHost.asStateFlow()

    private val _loginNotice = MutableStateFlow<ErrorPresentation?>(null)
    val loginNotice: StateFlow<ErrorPresentation?> = _loginNotice.asStateFlow()

    private val _selectedAppId = MutableStateFlow(settingsStore.appId)
    val selectedAppId: StateFlow<String> = _selectedAppId.asStateFlow()

    private val _availableApps = MutableStateFlow<List<EneoApp>>(emptyList())
    val availableApps: StateFlow<List<EneoApp>> = _availableApps.asStateFlow()

    private val _personalSpaceId = MutableStateFlow<String?>(null)
    val personalSpaceId: StateFlow<String?> = _personalSpaceId.asStateFlow()

    private val _isLoadingApps = MutableStateFlow(false)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

    private val _appLoadError = MutableStateFlow<ErrorPresentation?>(null)
    val appLoadError: StateFlow<ErrorPresentation?> = _appLoadError.asStateFlow()

    val isServerUrlValid: Boolean
        get() = ApiSettingsStore.makeBaseUrl(_serverUrlDraft.value) != null

    fun setServerUrlDraft(value: String) {
        _serverUrlDraft.value = value
    }

    fun goToWelcome() {
        _step.value = OnboardingStep.Welcome
    }

    fun goToServerUrl() {
        _step.value = OnboardingStep.ServerUrl
    }

    fun goToVerifyServer() {
        _step.value = OnboardingStep.VerifyServer
        startVerification()
    }

    fun goToLogin() {
        _loginPhase.value = if (authService.isAuthenticated.value) LoginPhase.SignedIn else LoginPhase.Prompt
        _step.value = OnboardingStep.Login
    }

    fun goToSelectApp() {
        _step.value = OnboardingStep.SelectApp
        loadAppsIfNeeded()
    }

    fun goBack() {
        when (_step.value) {
            OnboardingStep.ServerUrl -> _step.value = OnboardingStep.Welcome
            OnboardingStep.VerifyServer -> {
                resetVerification()
                _step.value = OnboardingStep.ServerUrl
            }
            OnboardingStep.Login -> {
                resetVerification()
                _step.value = OnboardingStep.VerifyServer
                startVerification()
            }
            OnboardingStep.SelectApp -> _step.value = OnboardingStep.Login
            else -> Unit
        }
    }

    fun retryVerification() {
        startVerification()
    }

    fun editServerAfterFailure() {
        resetVerification()
        _step.value = OnboardingStep.ServerUrl
    }

    private fun resetVerification() {
        _verifyPhase.value = VerifyPhase.Idle
        _verifiedHost.value = null
        _verifyErrorMessage.value = null
    }

    fun startVerification() {
        viewModelScope.launch {
            _verifyPhase.value = VerifyPhase.Running
            _verifyErrorMessage.value = null
            val normalized = ApiSettingsStore.normalizedBaseUrl(_serverUrlDraft.value)
            if (ApiSettingsStore.makeBaseUrl(normalized) == null) {
                _verifyErrorMessage.value = app.getString(se.iteca.eneotaltilltext.R.string.onboarding_verify_failed_message)
                _verifyPhase.value = VerifyPhase.Failure
                return@launch
            }
            _serverUrlDraft.value = ApiSettingsStore.displayBaseUrl(normalized)
            settingsStore.baseUrl = normalized
            try {
                uploadService.probeServerReachability()
                _verifiedHost.value = settingsStore.callbackHost()
                _verifyPhase.value = VerifyPhase.Success
            } catch (error: Exception) {
                _verifyErrorMessage.value = ErrorPresentation.from(app, error).message
                    ?: app.getString(se.iteca.eneotaltilltext.R.string.onboarding_verify_failed_message)
                _verifyPhase.value = VerifyPhase.Failure
            }
        }
    }

    fun beginLogin() {
        _loginPhase.value = LoginPhase.SigningIn
        _loginNotice.value = null
        authService.clearError()
        viewModelScope.launch {
            try {
                val url = authService.beginSignIn()
                val host = settingsStore.callbackHost()
                if (host.isNullOrBlank()) {
                    authService.cancelSignIn()
                    _loginNotice.value = ErrorPresentation(
                        title = app.getString(se.iteca.eneotaltilltext.R.string.onboarding_login_open_failed_title),
                        message = app.getString(se.iteca.eneotaltilltext.R.string.onboarding_login_open_failed_message),
                    )
                    _loginPhase.value = LoginPhase.Prompt
                    return@launch
                }
                _authorizationUrl.value = url
                _callbackHost.value = host
            } catch (_: Exception) {
                if (authService.isSigningIn.value) {
                    authService.cancelSignIn()
                }
                _loginNotice.value = authService.error.value
                    ?: ErrorPresentation(
                        title = app.getString(se.iteca.eneotaltilltext.R.string.onboarding_login_open_failed_title),
                        message = app.getString(se.iteca.eneotaltilltext.R.string.onboarding_login_open_failed_message),
                    )
                _loginPhase.value = LoginPhase.Prompt
            }
        }
    }

    fun cancelLogin() {
        _authorizationUrl.value = null
        _callbackHost.value = null
        authService.cancelSignIn()
        _loginPhase.value = LoginPhase.Prompt
    }

    fun completeLogin(callback: Uri) {
        _authorizationUrl.value = null
        _callbackHost.value = null
        viewModelScope.launch {
            authService.completeSignIn(callback)
            if (authService.isAuthenticated.value) {
                _loginPhase.value = LoginPhase.SignedIn
            } else {
                _loginNotice.value = authService.error.value
                    ?: ErrorPresentation(
                        title = app.getString(se.iteca.eneotaltilltext.R.string.login_failed_title),
                        message = app.getString(se.iteca.eneotaltilltext.R.string.login_failed_message),
                    )
                _loginPhase.value = LoginPhase.Prompt
            }
        }
    }

    fun dismissLoginNotice() {
        _loginNotice.value = null
        _loginPhase.value = LoginPhase.Prompt
    }

    fun selectApp(appId: String) {
        _selectedAppId.value = appId
        settingsStore.appId = appId
        _availableApps.value.firstOrNull { it.id == appId }?.let { app ->
            settingsStore.persistDefaultAppDisplayName(app.name, app.spaceName, app.id)
        }
    }

    fun completeOnboarding() {
        val selected = _selectedAppId.value.trim()
        if (selected.isNotEmpty()) {
            settingsStore.appId = selected
            _availableApps.value.firstOrNull { it.id == selected }?.let { app ->
                settingsStore.persistDefaultAppDisplayName(app.name, app.spaceName, app.id)
            }
        }
        settingsStore.onboardingCompleted = true
    }

    private fun loadAppsIfNeeded() {
        if (_isLoadingApps.value || _availableApps.value.isNotEmpty()) return
        viewModelScope.launch {
            _isLoadingApps.value = true
            _appLoadError.value = null
            _personalSpaceId.value = uploadService.fetchPersonalSpaceId()
            try {
                val apps = uploadService.fetchApps()
                _availableApps.value = apps
                if (apps.none { it.id == _selectedAppId.value }) {
                    // Preselect only when there is nothing to choose between.
                    val preselected = if (apps.size == 1) apps[0].id else ""
                    _selectedAppId.value = preselected
                    settingsStore.appId = preselected
                }
                apps.firstOrNull { it.id == _selectedAppId.value }?.let { app ->
                    settingsStore.persistDefaultAppDisplayName(app.name, app.spaceName, app.id)
                }
            } catch (error: Exception) {
                _appLoadError.value = ErrorPresentation.from(app, error)
            }
            _isLoadingApps.value = false
        }
    }

    companion object {
        val Factory = eneoViewModelFactory { container ->
            OnboardingViewModel(
                app = container.application,
                settingsStore = container.apiSettings,
                uploadService = container.uploadService,
                authService = container.authService,
            )
        }
    }
}
