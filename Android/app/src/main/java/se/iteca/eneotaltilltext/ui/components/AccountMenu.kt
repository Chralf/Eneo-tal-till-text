package se.iteca.eneotaltilltext.ui.components

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import se.iteca.eneotaltilltext.EneoApplication
import se.iteca.eneotaltilltext.R
import se.iteca.eneotaltilltext.data.ApiSettingsStore
import se.iteca.eneotaltilltext.network.ServerReachability
import se.iteca.eneotaltilltext.ui.auth.EneoConnectSheet

enum class AccountMenuStyle {
    /** Icon button with a dropdown, for the start screen top bar. */
    Toolbar,

    /** One row with status and the sign-in/out button, for Settings. */
    Row,
}

/**
 * Shows whether the user is signed in to Eneo and whether Eneo answers, and
 * offers sign-in or sign-out.
 */
@Composable
fun AccountMenu(
    style: AccountMenuStyle,
    modifier: Modifier = Modifier,
    onOpenSettings: (() -> Unit)? = null,
    onSignedIn: (() -> Unit)? = null,
) {
    val container = (LocalContext.current.applicationContext as EneoApplication).container
    val authService = container.authService
    val uploadService = container.uploadService
    val settingsStore = container.apiSettings

    val isAuthenticated by authService.isAuthenticated.collectAsStateWithLifecycle()
    val reachability by uploadService.serverReachability.collectAsStateWithLifecycle()
    val isChecking by uploadService.isCheckingReachability.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var expanded by remember { mutableStateOf(false) }
    var showConnect by remember { mutableStateOf(false) }
    var showUnreachable by remember { mutableStateOf(false) }
    var showSignOut by remember { mutableStateOf(false) }

    // Read on every recomposition: Settings changes the address in place.
    val serverHost = ApiSettingsStore.makeBaseUrl(settingsStore.baseUrl)?.let { Uri.parse(it).host }

    val statusTitle = when {
        serverHost == null -> stringResource(R.string.account_not_connected)
        !isAuthenticated -> stringResource(R.string.settings_signed_out)
        else -> stringResource(R.string.settings_signed_in)
    }
    // Reachability has its own line; being unreachable is normal outside the
    // organisation's network and never hides the account state.
    val statusSubtitle = when {
        serverHost == null -> null
        isChecking && reachability == ServerReachability.Unknown -> stringResource(R.string.settings_checking_server)
        reachability == ServerReachability.Reachable -> stringResource(R.string.account_connected_to, serverHost)
        reachability == ServerReachability.Unreachable -> stringResource(R.string.account_unreachable)
        else -> null
    }
    val accountDescription = stringResource(R.string.account_title)
    val accessibilityText = listOfNotNull(accountDescription, statusTitle, statusSubtitle).joinToString(", ")

    fun beginSignIn() {
        scope.launch {
            try {
                // Off-network the login page would only fail to load.
                uploadService.probeServerReachability()
            } catch (_: Exception) {
                showUnreachable = true
                return@launch
            }
            if (authService.isSigningIn.value) {
                authService.cancelSignIn()
            }
            showConnect = true
        }
    }

    when (style) {
        AccountMenuStyle.Toolbar -> Box(modifier) {
            IconButton(
                onClick = { expanded = true },
                modifier = Modifier.semantics { contentDescription = accessibilityText },
            ) {
                Icon(
                    if (isAuthenticated) Icons.Filled.AccountCircle else Icons.Outlined.AccountCircle,
                    contentDescription = null,
                    // Grey whenever Eneo does not answer; tinted only while it can be used.
                    tint = if (isAuthenticated && reachability == ServerReachability.Reachable) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text(statusTitle) }, onClick = {}, enabled = false)
                statusSubtitle?.let { subtitle ->
                    DropdownMenuItem(text = { Text(subtitle) }, onClick = {}, enabled = false)
                }
                HorizontalDivider()
                when {
                    serverHost == null -> onOpenSettings?.let { openSettings ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.account_set_server)) },
                            leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                            onClick = {
                                expanded = false
                                openSettings()
                            },
                        )
                    }
                    isAuthenticated -> DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_sign_out), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.AutoMirrored.Filled.Logout,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            expanded = false
                            showSignOut = true
                        },
                    )
                    else -> DropdownMenuItem(
                        text = { Text(stringResource(R.string.account_sign_in)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null) },
                        onClick = {
                            expanded = false
                            beginSignIn()
                        },
                    )
                }
            }
        }

        AccountMenuStyle.Row -> Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(statusTitle, style = MaterialTheme.typography.bodyLarge)
                statusSubtitle?.let { subtitle ->
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (serverHost != null) {
                Spacer(Modifier.width(12.dp))
                if (isAuthenticated) {
                    TextButton(onClick = { showSignOut = true }) {
                        Text(stringResource(R.string.settings_sign_out), color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    TextButton(onClick = { beginSignIn() }) {
                        Text(stringResource(R.string.account_sign_in))
                    }
                }
            }
        }
    }

    if (showConnect) {
        EneoConnectSheet(
            authService = authService,
            settingsStore = settingsStore,
            onFinished = { signedIn ->
                showConnect = false
                if (signedIn) onSignedIn?.invoke()
            },
        )
    }

    if (showUnreachable) {
        AlertDialog(
            onDismissRequest = { showUnreachable = false },
            title = { Text(stringResource(R.string.account_unreachable_title)) },
            text = { Text(stringResource(R.string.account_unreachable_message)) },
            confirmButton = {
                TextButton(onClick = { showUnreachable = false }) { Text(stringResource(R.string.ok)) }
            },
        )
    }

    if (showSignOut) {
        AlertDialog(
            onDismissRequest = { showSignOut = false },
            title = { Text(stringResource(R.string.settings_sign_out_title)) },
            text = { Text(stringResource(R.string.settings_sign_out_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showSignOut = false
                    authService.signOut()
                }) {
                    Text(stringResource(R.string.settings_sign_out), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOut = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
