package se.iteca.eneotaltilltext.ui.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import se.iteca.eneotaltilltext.R
import se.iteca.eneotaltilltext.data.ApiSettingsStore
import se.iteca.eneotaltilltext.data.ErrorPresentation
import se.iteca.eneotaltilltext.data.RecordingRepository
import se.iteca.eneotaltilltext.data.RecordingRetention
import se.iteca.eneotaltilltext.network.EneoApp
import se.iteca.eneotaltilltext.network.EneoAuthService
import se.iteca.eneotaltilltext.network.RecordingUploadService
import se.iteca.eneotaltilltext.network.ServerReachability
import se.iteca.eneotaltilltext.ui.eneoViewModelFactory

class SettingsViewModel(
    private val settingsStore: ApiSettingsStore,
    private val repository: RecordingRepository,
    private val uploadService: RecordingUploadService,
    val authService: EneoAuthService,
    private val app: Application,
) : ViewModel() {
    private val _draftBaseUrl = MutableStateFlow(ApiSettingsStore.displayBaseUrl(settingsStore.baseUrl))
    val draftBaseUrl: StateFlow<String> = _draftBaseUrl.asStateFlow()

    private var committedBaseUrl = ApiSettingsStore.normalizedBaseUrl(settingsStore.baseUrl)

    private val _appId = MutableStateFlow(settingsStore.appId)
    val appId: StateFlow<String> = _appId.asStateFlow()

    private val _autoConvertAfterRecording = MutableStateFlow(settingsStore.autoConvertAfterRecording)
    val autoConvertAfterRecording: StateFlow<Boolean> = _autoConvertAfterRecording.asStateFlow()

    private val _retentionDays = MutableStateFlow(settingsStore.retentionDays)
    val retentionDays: StateFlow<Int> = _retentionDays.asStateFlow()

    private val _retentionWarningCount = MutableStateFlow<Int?>(null)
    val retentionWarningCount: StateFlow<Int?> = _retentionWarningCount.asStateFlow()

    private var pendingRetentionDays: Int? = null

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    private val _isCheckingServer = MutableStateFlow(false)
    val isCheckingServer: StateFlow<Boolean> = _isCheckingServer.asStateFlow()

    private val _isLoadingApps = MutableStateFlow(false)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

    private val _availableApps = MutableStateFlow<List<EneoApp>>(emptyList())
    val availableApps: StateFlow<List<EneoApp>> = _availableApps.asStateFlow()

    private val _personalSpaceId = MutableStateFlow<String?>(null)
    val personalSpaceId: StateFlow<String?> = _personalSpaceId.asStateFlow()

    private val _notice = MutableStateFlow<ErrorPresentation?>(null)
    val notice: StateFlow<ErrorPresentation?> = _notice.asStateFlow()

    /** Shared with the account menu, so every screen shows the same answer. */
    val serverReachability: StateFlow<ServerReachability> = uploadService.serverReachability

    val isBaseUrlValid: Boolean
        get() = ApiSettingsStore.makeBaseUrl(_draftBaseUrl.value) != null

    fun setDraftBaseUrl(value: String) {
        _draftBaseUrl.value = value
    }

    fun setAppId(value: String) {
        _appId.value = value
        persistSelectedDefaultApp()
    }

    fun setAutoConvertAfterRecording(value: Boolean) {
        settingsStore.autoConvertAfterRecording = value
        _autoConvertAfterRecording.value = value
    }

    fun requestRetentionDays(value: Int) {
        val clamped = RecordingRetention.clamped(value)
        if (clamped == _retentionDays.value) return
        viewModelScope.launch {
            val currentlyExpired = repository.countWouldExpire(_retentionDays.value)
            val wouldExpire = repository.countWouldExpire(clamped)
            if (wouldExpire > currentlyExpired) {
                pendingRetentionDays = clamped
                _retentionWarningCount.value = wouldExpire
            } else {
                applyRetentionDays(clamped)
            }
        }
    }

    fun confirmRetentionChange() {
        val pending = pendingRetentionDays ?: return
        applyRetentionDays(pending)
        pendingRetentionDays = null
        _retentionWarningCount.value = null
    }

    fun dismissRetentionWarning() {
        pendingRetentionDays = null
        _retentionWarningCount.value = null
    }

    private fun applyRetentionDays(value: Int) {
        settingsStore.retentionDays = value
        _retentionDays.value = value
    }

    fun commitAndDismiss(onDismiss: () -> Unit) {
        commitServerChange()
        onDismiss()
    }

    fun commitServerChange() {
        commitServerChangeWithoutReachabilityRefresh()
        refreshServerReachability()
    }

    fun dismissNotice() {
        _notice.value = null
    }

    fun refreshAndLoad() {
        viewModelScope.launch {
            authService.refreshAuthenticationState()
            refreshServerReachability()
        }
    }

    fun refreshServerReachability() {
        viewModelScope.launch {
            uploadService.refreshServerReachability()
        }
    }

    /** Probe server, then fetch apps. Call [onReady] only when reachable (picker can open). */
    fun beginSelectDefaultApp(onReady: () -> Unit) {
        if (_isCheckingServer.value || _isLoadingApps.value) return
        if (!isBaseUrlValid) return
        commitServerChangeWithoutReachabilityRefresh()
        viewModelScope.launch {
            _isCheckingServer.value = true
            try {
                uploadService.probeServerReachability()
                _isCheckingServer.value = false
                onReady()
                loadApps()
            } catch (error: Exception) {
                _notice.value = ErrorPresentation.from(app, error)
                _isCheckingServer.value = false
            }
        }
    }

    fun testCommunication() {
        if (!isBaseUrlValid) return
        commitServerChangeWithoutReachabilityRefresh()
        _isTesting.value = true
        viewModelScope.launch {
            try {
                uploadService.probeServerReachability()
            } catch (error: Exception) {
                _notice.value = ErrorPresentation.from(app, error)
                _isTesting.value = false
                return@launch
            }

            if (!authService.isAuthenticated.value) {
                _notice.value = ErrorPresentation(
                    title = app.getString(R.string.settings_comm_ok_title),
                    message = app.getString(R.string.settings_check_server_ok),
                )
                _isTesting.value = false
                return@launch
            }

            try {
                authService.verifySession()
            } catch (error: Exception) {
                val presentation = ErrorPresentation.from(app, error)
                if (presentation.requiresSignIn) {
                    authService.handleExpiredSession()
                }
                _notice.value = presentation
                _isTesting.value = false
                return@launch
            }

            try {
                val apps = uploadService.fetchApps()
                _availableApps.value = apps
                reconcileDefaultApp(apps)
                persistSelectedDefaultApp()
                _notice.value = ErrorPresentation(
                    title = app.getString(R.string.settings_comm_ok_title),
                    message = app.getString(R.string.settings_comm_ok_message, apps.size),
                )
            } catch (error: Exception) {
                val presentation = ErrorPresentation.from(app, error)
                if (presentation.requiresSignIn) {
                    authService.handleExpiredSession()
                }
                _notice.value = presentation
            }
            _isTesting.value = false
        }
    }

    fun loadApps() {
        if (_isLoadingApps.value) return
        if (!authService.isAuthenticated.value || !isBaseUrlValid) return
        _isLoadingApps.value = true
        viewModelScope.launch {
            try {
                _personalSpaceId.value = uploadService.fetchPersonalSpaceId()
                val apps = uploadService.fetchApps()
                _availableApps.value = apps
                reconcileDefaultApp(apps)
                apps.firstOrNull { it.id == settingsStore.appId }?.let { defaultApp ->
                    settingsStore.persistDefaultAppDisplayName(defaultApp.name, defaultApp.spaceName, defaultApp.id)
                }
                persistSelectedDefaultApp()
            } catch (error: Exception) {
                val presentation = ErrorPresentation.from(app, error)
                if (presentation.requiresSignIn) {
                    authService.handleExpiredSession()
                }
                _notice.value = presentation
            }
            _isLoadingApps.value = false
        }
    }

    fun storedDefaultAppDisplayName(): String? = settingsStore.storedDefaultAppDisplayName()

    fun hasDefaultApp(): Boolean = _appId.value.trim().isNotEmpty()

    /** Keeps the stored default consistent with what the server offers. */
    private fun reconcileDefaultApp(apps: List<EneoApp>) {
        if (apps.any { it.id == _appId.value }) return
        _appId.value = if (apps.size == 1) apps[0].id else ""
        settingsStore.appId = _appId.value
        if (_appId.value.isEmpty()) {
            settingsStore.defaultAppName = ""
            settingsStore.defaultAppSpaceName = ""
        }
    }

    private fun commitServerChangeWithoutReachabilityRefresh() {
        val normalized = ApiSettingsStore.normalizedBaseUrl(_draftBaseUrl.value)
        if (ApiSettingsStore.makeBaseUrl(normalized) == null) return
        _draftBaseUrl.value = ApiSettingsStore.displayBaseUrl(normalized)
        settingsStore.baseUrl = normalized
        if (normalized != committedBaseUrl) {
            // App IDs belong to one Eneo installation, so the default app
            // from the previous server would only fail on the new one.
            if (committedBaseUrl.isNotEmpty()) {
                _appId.value = ""
                settingsStore.appId = ""
                settingsStore.defaultAppName = ""
                settingsStore.defaultAppSpaceName = ""
                val uploadingId = uploadService.uploadingRecordingId.value
                val exclude = if (uploadingId != null && uploadService.isUploading.value) {
                    setOf(uploadingId)
                } else {
                    emptySet()
                }
                viewModelScope.launch {
                    repository.clearServerBoundUploadIds(exclude)
                }
            }
            committedBaseUrl = normalized
            authService.signOut()
            resetConnectionState()
        }
    }

    private fun persistSelectedDefaultApp() {
        val trimmed = _appId.value.trim()
        if (trimmed.isEmpty()) {
            settingsStore.appId = ""
            settingsStore.defaultAppName = ""
            settingsStore.defaultAppSpaceName = ""
            return
        }
        val appChoice = _availableApps.value.firstOrNull { it.id == trimmed } ?: return
        settingsStore.appId = appChoice.id
        settingsStore.persistDefaultAppDisplayName(appChoice.name, appChoice.spaceName, appChoice.id)
    }

    private fun resetConnectionState() {
        _availableApps.value = emptyList()
        _personalSpaceId.value = null
        _notice.value = null
        _isLoadingApps.value = false
        _isCheckingServer.value = false
    }

    companion object {
        val Factory = eneoViewModelFactory { container ->
            SettingsViewModel(
                settingsStore = container.apiSettings,
                repository = container.repository,
                uploadService = container.uploadService,
                authService = container.authService,
                app = container.application,
            )
        }
    }
}
