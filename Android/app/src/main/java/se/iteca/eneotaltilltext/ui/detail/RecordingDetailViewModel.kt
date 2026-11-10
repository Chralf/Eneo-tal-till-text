package se.iteca.eneotaltilltext.ui.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import se.iteca.eneotaltilltext.EneoApplication
import se.iteca.eneotaltilltext.audio.AudioPlayer
import se.iteca.eneotaltilltext.data.ApiSettingsStore
import se.iteca.eneotaltilltext.data.ErrorPresentation
import se.iteca.eneotaltilltext.data.RecordingEntity
import se.iteca.eneotaltilltext.data.RecordingRepository
import se.iteca.eneotaltilltext.network.EneoApp
import se.iteca.eneotaltilltext.network.EneoAuthService
import se.iteca.eneotaltilltext.network.RecordingUploadService
import se.iteca.eneotaltilltext.network.UploadForegroundService
import se.iteca.eneotaltilltext.network.formatAppDisplayName
import se.iteca.eneotaltilltext.util.Formatters

class RecordingDetailViewModel(
    application: Application,
    private val recordingId: String,
    private val repository: RecordingRepository,
    val uploadService: RecordingUploadService,
    val authService: EneoAuthService,
    val settingsStore: ApiSettingsStore,
) : AndroidViewModel(application) {
    val player = AudioPlayer()
    val recording: StateFlow<RecordingEntity?> = repository.observe(recordingId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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

    private val _preflightError = MutableStateFlow<ErrorPresentation?>(null)
    val preflightError: StateFlow<ErrorPresentation?> = _preflightError.asStateFlow()

    private val _isCheckingServer = MutableStateFlow(false)
    val isCheckingServer: StateFlow<Boolean> = _isCheckingServer.asStateFlow()

    private val _isRecoveringAudio = MutableStateFlow(false)
    val isRecoveringAudio: StateFlow<Boolean> = _isRecoveringAudio.asStateFlow()

    private val _audioRecoveryFailed = MutableStateFlow(false)
    val audioRecoveryFailed: StateFlow<Boolean> = _audioRecoveryFailed.asStateFlow()

    private val _isScrubbing = MutableStateFlow(false)
    private var pollJob: Job? = null
    private var loadedDurationForId: String? = null
    private var isDeleted = false

    init {
        viewModelScope.launch {
            recording.filterNotNull().collect { current ->
                val preferred = current.uploadAppID?.trim().orEmpty()
                    .ifEmpty { settingsStore.appId }
                if (_selectedAppId.value.isEmpty() || _availableApps.value.isEmpty()) {
                    _selectedAppId.value = preferred
                }
                if (loadedDurationForId != current.id) {
                    loadedDurationForId = current.id
                    val file = repository.fileFor(current)
                    player.loadDuration(file, current.duration)
                }
            }
        }
        viewModelScope.launch {
            authService.isAuthenticated.collect { signedIn ->
                if (!signedIn) {
                    _availableApps.value = emptyList()
                    _personalSpaceId.value = null
                    _isLoadingApps.value = false
                    _appLoadError.value = null
                }
            }
        }
        pollJob = viewModelScope.launch {
            while (isActive) {
                if (!_isScrubbing.value) player.pollPosition()
                delay(200)
            }
        }
        viewModelScope.launch {
            recording.filterNotNull().first()
            recoverMissingAudioIfPossible()
        }
    }

    fun retryAudioRecovery() {
        viewModelScope.launch {
            recoverMissingAudioIfPossible()
        }
    }

    private suspend fun recoverMissingAudioIfPossible() {
        if (_isRecoveringAudio.value) return
        val current = repository.get(recordingId) ?: return
        if (repository.audioExists(current)) {
            _audioRecoveryFailed.value = false
            return
        }
        if (repository.pendingPartFiles(current).isEmpty()) {
            _audioRecoveryFailed.value = false
            return
        }
        _isRecoveringAudio.value = true
        _audioRecoveryFailed.value = false
        val recovered = repository.recoverAudioFile(current)
        _isRecoveringAudio.value = false
        _audioRecoveryFailed.value = !recovered
        if (recovered) {
            val updated = repository.get(recordingId) ?: current
            loadedDurationForId = updated.id
            player.loadDuration(repository.fileFor(updated), updated.duration)
        }
    }

    fun saveTitle(title: String) {
        if (isDeleted) return
        val trimmed = title.trim()
        // Skip empty while the user is still editing; commitTitle restores the
        // default so a blank row never reaches the list.
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            repository.updateTitle(recordingId, trimmed)
        }
    }

    fun commitTitle(title: String) {
        if (isDeleted) return
        val trimmed = title.trim()
        viewModelScope.launch {
            val resolved = if (trimmed.isEmpty()) {
                val current = repository.get(recordingId) ?: return@launch
                Formatters.recordingTitle(getApplication(), current.dateCreated)
            } else {
                trimmed
            }
            repository.updateTitle(recordingId, resolved)
        }
    }

    fun setSelectedApp(appId: String) {
        _selectedAppId.value = appId
        viewModelScope.launch {
            val app = _availableApps.value.firstOrNull { it.id == appId }
            repository.updateSelectedApp(
                id = recordingId,
                appId = appId,
                appName = app?.name,
                spaceName = app?.spaceName,
            )
            if (app != null) {
                settingsStore.persistDefaultAppDisplayName(app.name, app.spaceName, app.id)
            }
        }
    }

    fun loadApps() {
        if (_isLoadingApps.value) return
        if (!authService.isAuthenticated.value) return
        _appLoadError.value = null
        _isLoadingApps.value = true
        viewModelScope.launch {
            try {
                _personalSpaceId.value = uploadService.fetchPersonalSpaceId()
                val apps = uploadService.fetchApps()
                _availableApps.value = apps
                if (apps.none { it.id == _selectedAppId.value }) {
                    // Fall back to the default app, or to the only app there is.
                    // Otherwise leave the choice to the user rather than silently
                    // picking the first one in the list.
                    _selectedAppId.value = apps.firstOrNull { it.id == settingsStore.appId }?.id
                        ?: if (apps.size == 1) apps[0].id else ""
                }
                apps.firstOrNull { it.id == settingsStore.appId }?.let { defaultApp ->
                    settingsStore.persistDefaultAppDisplayName(defaultApp.name, defaultApp.spaceName, defaultApp.id)
                }
                if (_selectedAppId.value.isNotEmpty()) {
                    setSelectedApp(_selectedAppId.value)
                }
            } catch (error: Exception) {
                val presentation = ErrorPresentation.from(getApplication(), error)
                if (presentation.requiresSignIn) {
                    authService.handleExpiredSession()
                }
                _appLoadError.value = presentation
            }
            _isLoadingApps.value = false
        }
    }

    /**
     * Probe the configured Eneo host. On failure sets [appLoadError] or [preflightError]
     * with network guidance and returns false.
     */
    fun withServerReachable(
        forAppSelection: Boolean,
        onReady: () -> Unit,
    ) {
        if (_isCheckingServer.value) return
        viewModelScope.launch {
            _isCheckingServer.value = true
            try {
                uploadService.probeServerReachability()
                if (forAppSelection) {
                    _appLoadError.value = null
                } else {
                    _preflightError.value = null
                }
                onReady()
            } catch (error: Exception) {
                val presentation = ErrorPresentation.from(getApplication(), error)
                if (forAppSelection) {
                    _appLoadError.value = presentation
                } else {
                    _preflightError.value = presentation
                }
            }
            _isCheckingServer.value = false
        }
    }

    fun clearPreflightError() {
        _preflightError.value = null
    }

    fun clearAppLoadError() {
        _appLoadError.value = null
    }

    fun dismissNotice() {
        _appLoadError.value = null
        _preflightError.value = null
        viewModelScope.launch {
            repository.clearUploadError(recordingId)
        }
    }

    fun noticeToShow(recording: RecordingEntity?): ErrorPresentation? {
        _appLoadError.value?.let { return it }
        _preflightError.value?.let { return it }
        return recording?.let { uploadErrorPresentation(it) }
    }

    fun audioExists(recording: RecordingEntity): Boolean = repository.audioExists(recording)

    fun togglePlayback() {
        val current = recording.value ?: return
        player.toggle(repository.fileFor(current))
    }

    fun seek(position: Double) {
        player.seek(position)
    }

    fun setScrubbing(scrubbing: Boolean) {
        _isScrubbing.value = scrubbing
        if (!scrubbing) {
            player.seek(player.position.value)
        }
    }

    fun selectedAppDisplayName(recording: RecordingEntity): String {
        if (_selectedAppId.value.trim().isEmpty()) {
            return getApplication<Application>().getString(se.iteca.eneotaltilltext.R.string.detail_select_app)
        }
        _availableApps.value.firstOrNull { it.id == _selectedAppId.value }?.let { return it.displayName }
        val name = recording.uploadAppName?.trim().orEmpty()
        if (name.isNotEmpty()) {
            val space = recording.uploadSpaceName?.trim().orEmpty()
            return formatAppDisplayName(name, space)
        }
        if (_selectedAppId.value == settingsStore.appId) {
            settingsStore.storedDefaultAppDisplayName()?.let { return it }
        }
        return getApplication<Application>().getString(se.iteca.eneotaltilltext.R.string.detail_default_app)
    }

    fun hasSelectedApp(): Boolean = _selectedAppId.value.trim().isNotEmpty()

    fun uploadErrorPresentation(recording: RecordingEntity): ErrorPresentation? {
        val stored = recording.uploadError?.trim().orEmpty()
        if (stored.isEmpty()) return null
        return ErrorPresentation.fromPersisted(getApplication(), stored)
    }

    fun upload() {
        if (uploadService.isUploading.value) return
        if (!settingsStore.hasAccessToken) return
        val appId = _selectedAppId.value.trim().ifEmpty { settingsStore.appId }
        if (appId.isEmpty()) return
        uploadService.markStarting(recordingId)
        viewModelScope.launch {
            repository.startUpload(
                id = recordingId,
                status = "processing",
                progress = getApplication<Application>().getString(
                    se.iteca.eneotaltilltext.R.string.status_uploading,
                ),
                appId = appId,
            )
            UploadForegroundService.start(getApplication(), recordingId, appId)
        }
    }

    fun cancelUpload() {
        UploadForegroundService.cancel(getApplication())
    }

    fun clearUploadError() {
        viewModelScope.launch {
            repository.clearUploadError(recordingId)
        }
    }

    fun delete(onDeleted: () -> Unit) {
        isDeleted = true
        viewModelScope.launch {
            player.stop(resetPosition = true)
            val current = repository.get(recordingId) ?: return@launch
            repository.delete(current)
            onDeleted()
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        player.stop()
    }

    companion object {
        fun factory(recordingId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as EneoApplication
                RecordingDetailViewModel(
                    application = app,
                    recordingId = recordingId,
                    repository = app.container.repository,
                    uploadService = app.container.uploadService,
                    authService = app.container.authService,
                    settingsStore = app.container.apiSettings,
                )
            }
        }
    }
}
