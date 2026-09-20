package se.iteca.eneotaltilltext.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.iteca.eneotaltilltext.data.RecordingEntity
import se.iteca.eneotaltilltext.data.RecordingRepository
import se.iteca.eneotaltilltext.ui.eneoViewModelFactory

class RecordingListViewModel(
    private val repository: RecordingRepository,
) : ViewModel() {
    val recordings: StateFlow<List<RecordingEntity>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(recording: RecordingEntity) {
        viewModelScope.launch {
            repository.delete(recording)
        }
    }

    companion object {
        val Factory = eneoViewModelFactory { RecordingListViewModel(it.repository) }
    }
}
