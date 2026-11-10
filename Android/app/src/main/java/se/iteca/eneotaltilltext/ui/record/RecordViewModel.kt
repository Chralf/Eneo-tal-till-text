package se.iteca.eneotaltilltext.ui.record

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import se.iteca.eneotaltilltext.EneoApplication
import se.iteca.eneotaltilltext.R
import se.iteca.eneotaltilltext.audio.AudioRecorder
import se.iteca.eneotaltilltext.audio.RecordingForegroundService
import se.iteca.eneotaltilltext.audio.RecordingState
import se.iteca.eneotaltilltext.data.RecordingEntity
import se.iteca.eneotaltilltext.data.RecordingRepository
import se.iteca.eneotaltilltext.util.Formatters

class RecordViewModel(
    application: Application,
    private val repository: RecordingRepository,
    val recorder: AudioRecorder,
) : AndroidViewModel(application) {
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var activeRecording: RecordingEntity? = null
    private val dbLock = Mutex()
    private var finalizeStarted = false

    fun clearError() {
        _errorMessage.value = null
    }

    fun startRecording() {
        if (recorder.isFinalizing.value) return
        try {
            val file = repository.createRecordingFile()
            recorder.onSegmentFinalized = { duration ->
                persistDuration(duration)
            }
            recorder.start(file)
            RecordingForegroundService.start(getApplication())
            val recording = RecordingEntity(
                title = Formatters.recordingTitle(getApplication()),
                audioFileName = file.name,
                duration = 0.0,
            )
            activeRecording = recording
            repository.setActiveRecordingId(recording.id)
            viewModelScope.launch { dbLock.withLock { repository.insert(recording) } }
            _errorMessage.value = null
        } catch (error: Exception) {
            recorder.onSegmentFinalized = null
            recorder.cancel(deleteFile = true)
            RecordingForegroundService.stop(getApplication())
            _errorMessage.value = getApplication<Application>().getString(R.string.error_start_recording)
        }
    }

    fun pause() {
        recorder.pause()
        persistDuration(recorder.recordingTime.value)
    }

    fun resume() {
        recorder.resume()
    }

    fun cancel() {
        recorder.onSegmentFinalized = null
        recorder.cancel(deleteFile = true)
        RecordingForegroundService.stop(getApplication())
        val recording = activeRecording
        activeRecording = null
        repository.setActiveRecordingId(null)
        if (recording != null) {
            viewModelScope.launch {
                dbLock.withLock {
                    repository.delete(recording)
                }
            }
        }
    }

    fun finalizeRecording(fromMaxDuration: Boolean, onSaved: (RecordingEntity?) -> Unit) {
        // A second tap on stop while the merge is running must not finalize twice.
        if (finalizeStarted || (!fromMaxDuration && recorder.isFinalizing.value)) return
        finalizeStarted = true
        viewModelScope.launch {
            val duration = if (fromMaxDuration && recorder.state.value == RecordingState.Idle) {
                recorder.lastCompletedDuration.takeIf { it > 0 }
                    ?: RecordingEntity.MAX_RECORDING_DURATION_SECONDS
            } else if (recorder.state.value != RecordingState.Idle || recorder.isFinalizing.value) {
                recorder.stop()
            } else {
                activeRecording?.duration ?: 0.0
            }
            recorder.onSegmentFinalized = null
            RecordingForegroundService.stop(getApplication())
            val recording = activeRecording
            if (recording == null) {
                onSaved(null)
                return@launch
            }
            val updated = recording.copy(duration = duration)
            activeRecording = null
            repository.setActiveRecordingId(null)
            dbLock.withLock { repository.insert(updated) }
            onSaved(updated)
        }
    }

    private fun persistDuration(duration: Double = recorder.recordingTime.value) {
        val recording = activeRecording ?: return
        val updated = recording.copy(duration = duration)
        activeRecording = updated
        viewModelScope.launch {
            dbLock.withLock { repository.update(updated) }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as EneoApplication
                RecordViewModel(app, app.container.repository, app.container.audioRecorder)
            }
        }
    }
}
