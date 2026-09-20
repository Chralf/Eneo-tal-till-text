package se.iteca.eneotaltilltext.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import se.iteca.eneotaltilltext.AppContainer
import se.iteca.eneotaltilltext.EneoApplication

inline fun <reified VM : ViewModel> eneoViewModelFactory(
    crossinline create: (AppContainer) -> VM,
): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val app = this[APPLICATION_KEY] as EneoApplication
        create(app.container)
    }
}
