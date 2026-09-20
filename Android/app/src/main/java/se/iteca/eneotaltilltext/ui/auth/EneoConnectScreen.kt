package se.iteca.eneotaltilltext.ui.auth

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import se.iteca.eneotaltilltext.R
import se.iteca.eneotaltilltext.data.ApiSettingsStore
import se.iteca.eneotaltilltext.data.ErrorPresentation
import se.iteca.eneotaltilltext.network.EneoAuthService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EneoConnectSheet(
    authService: EneoAuthService,
    settingsStore: ApiSettingsStore,
    onFinished: (Boolean) -> Unit,
) {
    val isSigningIn by authService.isSigningIn.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = {
            if (!isSigningIn) {
                authService.cancelSignIn()
                onFinished(false)
            }
        },
        sheetState = sheetState,
    ) {
        EneoConnectContent(
            authService = authService,
            settingsStore = settingsStore,
            onFinished = onFinished,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EneoConnectContent(
    authService: EneoAuthService,
    settingsStore: ApiSettingsStore,
    onFinished: (Boolean) -> Unit,
) {
    val isSigningIn by authService.isSigningIn.collectAsStateWithLifecycle()
    val statusText by authService.statusText.collectAsStateWithLifecycle()
    val error by authService.error.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var authorizationUrl by remember { mutableStateOf<Uri?>(null) }
    var callbackHost by remember { mutableStateOf<String?>(null) }
    var didReceiveCallback by remember { mutableStateOf(false) }
    var didStartSignIn by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<ErrorPresentation?>(null) }

    fun cancel() {
        if (isSigningIn && !didReceiveCallback) {
            authService.cancelSignIn()
        }
        onFinished(false)
    }

    fun beginSignIn() {
        didReceiveCallback = false
        authService.clearError()
        scope.launch {
            try {
                val url = authService.beginSignIn()
                val host = settingsStore.callbackHost()
                if (host.isNullOrBlank()) {
                    authService.cancelSignIn()
                    notice = ErrorPresentation(
                        title = "Kunde inte öppna inloggning",
                        message = "Kontrollera serveradressen i Inställningar.",
                    )
                    return@launch
                }
                authorizationUrl = url
                callbackHost = host
            } catch (_: Exception) {
                if (authService.isSigningIn.value) {
                    authService.cancelSignIn()
                }
                notice = authService.error.value
                    ?: ErrorPresentation(
                        title = "Kunde inte öppna inloggning",
                        message = "Kontrollera nätverket och försök igen.",
                    )
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!didStartSignIn) {
            didStartSignIn = true
            beginSignIn()
        }
    }

    LaunchedEffect(error) {
        val current = error
        if (current != null && authorizationUrl == null && !isSigningIn) {
            notice = current
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.login_title)) },
                navigationIcon = {
                    TextButton(onClick = { cancel() }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            Text(
                statusText ?: stringResource(R.string.login_opening_short),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }

    notice?.let { presentation ->
        AlertDialog(
            onDismissRequest = {
                notice = null
                authService.clearError()
                onFinished(false)
            },
            title = { Text(presentation.title) },
            text = {
                presentation.message?.takeIf { it.isNotBlank() }?.let { Text(it) }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        notice = null
                        authService.clearError()
                        onFinished(false)
                    },
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }

    val loginUrl = authorizationUrl
    val host = callbackHost
    if (loginUrl != null && host != null) {
        Dialog(
            onDismissRequest = {
                if (!didReceiveCallback) {
                    authorizationUrl = null
                    callbackHost = null
                    authService.cancelSignIn()
                    onFinished(false)
                }
            },
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true),
        ) {
            BackHandler {
                authorizationUrl = null
                callbackHost = null
                authService.cancelSignIn()
                onFinished(false)
            }
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.login_title)) },
                        navigationIcon = {
                            TextButton(onClick = {
                                authorizationUrl = null
                                callbackHost = null
                                authService.cancelSignIn()
                                onFinished(false)
                            }) {
                                Text(stringResource(R.string.cancel))
                            }
                        },
                    )
                },
            ) { innerPadding ->
                EneoLoginWebView(
                    authorizationUrl = loginUrl,
                    callbackHost = host,
                    onCallback = { callback ->
                        didReceiveCallback = true
                        authorizationUrl = null
                        callbackHost = null
                        scope.launch {
                            authService.completeSignIn(callback)
                            didReceiveCallback = false
                            if (authService.isAuthenticated.value) {
                                onFinished(true)
                            } else {
                                notice = authService.error.value
                                    ?: ErrorPresentation(
                                        title = "Inloggningen kunde inte slutföras",
                                        message = "Försök igen.",
                                    )
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                )
            }
        }
    }
}
