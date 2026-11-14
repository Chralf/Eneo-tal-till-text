package se.iteca.eneotaltilltext

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import se.iteca.eneotaltilltext.ui.detail.RecordingDetailScreen
import se.iteca.eneotaltilltext.ui.info.InfoScreen
import se.iteca.eneotaltilltext.ui.list.RecordingListScreen
import se.iteca.eneotaltilltext.ui.onboarding.OnboardingScreen
import se.iteca.eneotaltilltext.ui.record.RecordScreen
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import se.iteca.eneotaltilltext.ui.settings.SettingsScreen
import se.iteca.eneotaltilltext.ui.theme.EneoTheme

class MainActivity : ComponentActivity() {
    private val importedRecordingIds = MutableSharedFlow<String>(extraBufferCapacity = 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleImportIntent(intent)
        setContent {
            EneoTheme {
                val app = application as EneoApplication
                var showOnboarding by remember { mutableStateOf(app.container.apiSettings.shouldShowOnboarding()) }
                if (showOnboarding) {
                    OnboardingScreen(onComplete = { showOnboarding = false })
                } else {
                    val navController = rememberNavController()
                    LaunchedEffect(Unit) {
                        importedRecordingIds.collectLatest { id ->
                            navController.navigate("detail/$id/false")
                        }
                    }
                    NavHost(navController = navController, startDestination = "list") {
                    composable("list") {
                        RecordingListScreen(
                            onOpenSettings = { navController.navigate("settings") },
                            onOpenInfo = { navController.navigate("info") },
                            onRecord = { navController.navigate("record") },
                            onOpenRecording = { id -> navController.navigate("detail/$id/false") },
                        )
                    }
                    composable("record") {
                        RecordScreen(
                            onFinished = { recording ->
                                val autoConvert = app.container.apiSettings.autoConvertAfterRecording
                                navController.navigate("detail/${recording.id}/$autoConvert") {
                                    popUpTo("list")
                                }
                            },
                            onDismiss = { navController.popBackStack() },
                            onOpenInfo = { navController.navigate("info") },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(onDismiss = { navController.popBackStack() })
                    }
                    composable("info") {
                        InfoScreen(onDismiss = { navController.popBackStack() })
                    }
                    composable(
                        route = "detail/{recordingId}/{autoConvert}",
                        arguments = listOf(
                            navArgument("recordingId") { type = NavType.StringType },
                            navArgument("autoConvert") { type = NavType.BoolType },
                        ),
                    ) { entry ->
                        val id = entry.arguments?.getString("recordingId").orEmpty()
                        val autoConvert = entry.arguments?.getBoolean("autoConvert") == true
                        RecordingDetailScreen(
                            recordingId = id,
                            onBack = { navController.popBackStack() },
                            autoStartUpload = autoConvert,
                        )
                    }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Retention must also apply when the app is merely brought back from
        // the background, not only at process start.
        val container = (application as EneoApplication).container
        container.repository.purgeExpiredOnForeground()
        // Let the account menu, Settings and the detail screen show the real
        // state before the user acts.
        lifecycleScope.launch {
            container.authService.refreshAuthenticationState()
            container.uploadService.refreshServerReachability()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleImportIntent(intent)
    }

    private fun handleImportIntent(intent: Intent?) {
        if (intent == null) return
        val uri = importUri(intent) ?: return
        val app = application as EneoApplication
        lifecycleScope.launch {
            runCatching {
                val recording = app.container.repository.importFromUri(uri)
                importedRecordingIds.emit(recording.id)
            }
        }
        intent.action = Intent.ACTION_MAIN
        intent.data = null
        intent.removeExtra(Intent.EXTRA_STREAM)
    }

    private fun importUri(intent: Intent): Uri? {
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
    }
}
