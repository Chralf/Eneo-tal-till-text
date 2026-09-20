package se.iteca.eneotaltilltext

import android.app.Application
import se.iteca.eneotaltilltext.audio.AudioRecorder
import se.iteca.eneotaltilltext.data.ApiSettingsStore
import se.iteca.eneotaltilltext.data.AppDatabase
import se.iteca.eneotaltilltext.data.RecordingFileStore
import se.iteca.eneotaltilltext.data.RecordingRepository
import se.iteca.eneotaltilltext.network.RecordingUploadService
import se.iteca.eneotaltilltext.network.EneoAuthService
import se.iteca.eneotaltilltext.notifications.TranscriptionNotificationService

class EneoApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        TranscriptionNotificationService.ensureChannel(this)
        container.apiSettings.migrateExistingUsersIfNeeded()
        container.repository.recoverOnLaunch(container.audioRecorder)
    }
}

class AppContainer(val application: Application) {
    private val database = AppDatabase.create(application)
    val fileStore = RecordingFileStore(application)
    val apiSettings = ApiSettingsStore(application)
    val repository = RecordingRepository(application, database.recordingDao(), fileStore, apiSettings)
    val audioRecorder = AudioRecorder(application)
    val uploadService = RecordingUploadService(application, apiSettings)
    val authService = EneoAuthService(application, apiSettings)
    val notifications = TranscriptionNotificationService(application)
}
