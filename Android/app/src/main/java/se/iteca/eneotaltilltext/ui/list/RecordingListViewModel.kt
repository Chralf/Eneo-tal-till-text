package se.iteca.eneotaltilltext.ui.list

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.iteca.eneotaltilltext.data.RecordingEntity
import se.iteca.eneotaltilltext.data.RecordingRepository
import se.iteca.eneotaltilltext.network.RecordingUploadService
import se.iteca.eneotaltilltext.network.UploadForegroundService
import se.iteca.eneotaltilltext.ui.eneoViewModelFactory

class RecordingListViewModel(
    private val application: Application,
    private val repository: RecordingRepository,
    private val uploadService: RecordingUploadService,
) : ViewModel() {
    val recordings: StateFlow<List<RecordingEntity>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(recording: RecordingEntity) {
        viewModelScope.launch {
            if (uploadService.isUploading(recording.id)) {
                UploadForegroundService.cancel(application)
            }
            repository.delete(recording)
        }
    }

    companion object {
        val Factory = eneoViewModelFactory { container ->
            RecordingListViewModel(
                application = container.application,
                repository = container.repository,
                uploadService = container.uploadService,
            )
        }
    }
}
