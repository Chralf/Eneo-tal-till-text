package se.iteca.eneotaltilltext.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import se.iteca.eneotaltilltext.R
import se.iteca.eneotaltilltext.ui.components.EneoAppPickerDialog
import se.iteca.eneotaltilltext.ui.components.RetentionDaysDialog
import se.iteca.eneotaltilltext.ui.components.retentionDaysLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onDismiss: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val draftBaseUrl by viewModel.draftBaseUrl.collectAsStateWithLifecycle()
    val appId by viewModel.appId.collectAsStateWithLifecycle()
    val isTesting by viewModel.isTesting.collectAsStateWithLifecycle()
    val isCheckingServer by viewModel.isCheckingServer.collectAsStateWithLifecycle()
    val isLoadingApps by viewModel.isLoadingApps.collectAsStateWithLifecycle()
    val availableApps by viewModel.availableApps.collectAsStateWithLifecycle()
    val personalSpaceId by viewModel.personalSpaceId.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val serverReachability by viewModel.serverReachability.collectAsStateWithLifecycle()
    val retentionDays by viewModel.retentionDays.collectAsStateWithLifecycle()
    val retentionWarningCount by viewModel.retentionWarningCount.collectAsStateWithLifecycle()
    val autoConvertAfterRecording by viewModel.autoConvertAfterRecording.collectAsStateWithLifecycle()
    val isAuthenticated by viewModel.authService.isAuthenticated.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val supportWebsiteUrl = stringResource(R.string.settings_support_website_url)
    var showSignOut by remember { mutableStateOf(false) }
    var showAppPicker by remember { mutableStateOf(false) }
    var showRetentionPicker by remember { mutableStateOf(false) }
    val isBaseUrlValid = ApiSettingsStoreValid(draftBaseUrl)
    val isSelectingApp = isCheckingServer || isLoadingApps
    val canManageAccount = serverReachability == SettingsServerReachability.Reachable


    LaunchedEffect(Unit) {
        viewModel.refreshAndLoad()
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            showAppPicker = false
        }
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.commitServerChange() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                actions = {
                    TextButton(onClick = { viewModel.commitAndDismiss(onDismiss) }) {
                        Text(stringResource(R.string.done))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionHeader(stringResource(R.string.settings_section_server))
            OutlinedTextField(
                value = draftBaseUrl,
                onValueChange = viewModel::setDraftBaseUrl,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_server_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
                ),
                isError = !isBaseUrlValid,
            )
            if (!isBaseUrlValid) {
                Text(
                    stringResource(R.string.settings_server_invalid),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            FooterText(stringResource(R.string.settings_server_footer))

            HorizontalDivider()
            SectionHeader(stringResource(R.string.settings_section_account))
            when (serverReachability) {
                SettingsServerReachability.Checking -> {
                    InfoLine(
                        stringResource(R.string.settings_account_status),
                        stringResource(R.string.settings_checking_server),
                        leading = {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        },
                    )
                }
                SettingsServerReachability.Unreachable -> {
                    InfoLine(
                        stringResource(R.string.settings_account_status),
                        stringResource(R.string.settings_server_unreachable),
                        leading = {
                            Icon(
                                Icons.Filled.WifiOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
                SettingsServerReachability.Reachable -> {
                    InfoLine(
                        stringResource(R.string.settings_account_status),
                        if (isAuthenticated) {
                            stringResource(R.string.settings_signed_in)
                        } else {
                            stringResource(R.string.settings_signed_out)
                        },
                        leading = {
                            Icon(
                                if (isAuthenticated) Icons.Filled.CheckCircle else Icons.Outlined.AccountCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                    if (isAuthenticated) {
                        TextButton(onClick = { showSignOut = true }) {
                            Text(stringResource(R.string.settings_sign_out), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            FooterText(stringResource(R.string.settings_account_footer))

            if (canManageAccount && isAuthenticated) {
                HorizontalDivider()
                SectionHeader(stringResource(R.string.settings_section_convert))
                val hasDefaultApp = appId.trim().isNotEmpty()
                val selectedName = when {
                    !hasDefaultApp -> stringResource(R.string.settings_select_app)
                    else -> availableApps.firstOrNull { it.id == appId }?.displayName
                        ?: viewModel.storedDefaultAppDisplayName()
                        ?: stringResource(R.string.settings_default_app)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isSelectingApp && isBaseUrlValid) {
                            viewModel.beginSelectDefaultApp { showAppPicker = true }
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        selectedName,
                        modifier = Modifier.weight(1f),
                        color = if (hasDefaultApp) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                    if (isSelectingApp) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.ChevronRight, contentDescription = null)
                    }
                }
                FooterText(stringResource(R.string.settings_convert_footer))
            }

            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.settings_auto_convert),
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = autoConvertAfterRecording,
                    onCheckedChange = viewModel::setAutoConvertAfterRecording,
                )
            }
            FooterText(stringResource(R.string.settings_auto_convert_footer))

            HorizontalDivider()
            SectionHeader(stringResource(R.string.settings_section_diagnostics))
            TextButton(
                onClick = { viewModel.testCommunication() },
                enabled = !isTesting && isBaseUrlValid,
            ) {
                if (isTesting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.padding(horizontal = 8.dp))
                    Text(stringResource(R.string.settings_testing_comm))
                } else {
                    Icon(Icons.Filled.Language, contentDescription = null)
                    Spacer(Modifier.padding(horizontal = 6.dp))
                    Text(stringResource(R.string.settings_test_comm))
                }
            }
            FooterText(stringResource(R.string.settings_diagnostics_footer))

            HorizontalDivider()
            SectionHeader(stringResource(R.string.settings_section_retention))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showRetentionPicker = true }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_retention_label), modifier = Modifier.weight(1f))
                Text(
                    retentionDaysLabel(retentionDays),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(Icons.Filled.ChevronRight, contentDescription = null)
            }
            FooterText(stringResource(R.string.settings_retention_footer))

            HorizontalDivider()
            Column(
                modifier = Modifier.padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.settings_support_free),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    stringResource(R.string.settings_support_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_SENDTO,
                            Uri.parse(
                                "mailto:christian@iteca.se?subject=" +
                                    Uri.encode("Supportavtal Eneo tal-till-text"),
                            ),
                        ),
                    )
                },
            ) {
                Icon(Icons.Filled.Email, contentDescription = null)
                Spacer(Modifier.padding(horizontal = 6.dp))
                Text(stringResource(R.string.settings_support_email))
            }
            TextButton(
                onClick = {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(supportWebsiteUrl),
                        ),
                    )
                },
            ) {
                Icon(Icons.Outlined.Language, contentDescription = null)
                Spacer(Modifier.padding(horizontal = 6.dp))
                Text(stringResource(R.string.settings_support_website))
            }

            HorizontalDivider()
            Text(
                stringResource(R.string.settings_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }


    notice?.let { presentation ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissNotice() },
            title = { Text(presentation.title) },
            text = {
                presentation.message?.takeIf { it.isNotBlank() }?.let { Text(it) }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissNotice() }) {
                    Text(stringResource(R.string.ok))
                }
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
                    viewModel.signOut()
                }) {
                    Text(stringResource(R.string.settings_sign_out), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOut = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showAppPicker) {
        EneoAppPickerDialog(
            apps = availableApps,
            selectedAppId = appId,
            personalSpaceId = personalSpaceId,
            onSelect = viewModel::setAppId,
            onDismiss = { showAppPicker = false },
        )
    }

    if (showRetentionPicker) {
        RetentionDaysDialog(
            selectedDays = retentionDays,
            onSelect = {
                showRetentionPicker = false
                viewModel.requestRetentionDays(it)
            },
            onDismiss = { showRetentionPicker = false },
        )
    }

    retentionWarningCount?.let { count ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissRetentionWarning() },
            title = { Text(stringResource(R.string.settings_retention_warning_title)) },
            text = { Text(stringResource(R.string.settings_retention_warning_message, count)) },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmRetentionChange() }) {
                    Text(stringResource(R.string.settings_retention_warning_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissRetentionWarning() }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ApiSettingsStoreValid(value: String): Boolean =
    se.iteca.eneotaltilltext.data.ApiSettingsStore.makeBaseUrl(value) != null

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FooterText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun InfoLine(
    label: String,
    value: String,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            leading?.invoke()
            Text(value)
        }
    }
}
