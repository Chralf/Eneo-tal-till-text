package se.iteca.eneotaltilltext.ui.onboarding

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import se.iteca.eneotaltilltext.R
import se.iteca.eneotaltilltext.data.ApiSettingsStore
import se.iteca.eneotaltilltext.network.EneoApp
import se.iteca.eneotaltilltext.network.groupEneoApps
import se.iteca.eneotaltilltext.network.pickerLabel
import se.iteca.eneotaltilltext.ui.auth.EneoLoginWebView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = viewModel(factory = OnboardingViewModel.Factory),
) {
    val step by viewModel.step.collectAsStateWithLifecycle()
    val verifyPhase by viewModel.verifyPhase.collectAsStateWithLifecycle()
    val loginPhase by viewModel.loginPhase.collectAsStateWithLifecycle()
    val authorizationUrl by viewModel.authorizationUrl.collectAsStateWithLifecycle()
    val callbackHost by viewModel.callbackHost.collectAsStateWithLifecycle()
    val loginNotice by viewModel.loginNotice.collectAsStateWithLifecycle()

    val showBack = step != OnboardingStep.Welcome && step != OnboardingStep.VerifyServer

    Scaffold(
        topBar = {
            if (showBack) {
                TopAppBar(
                    title = { },
                    navigationIcon = {
                        TextButton(onClick = viewModel::goBack) {
                            Text(stringResource(R.string.onboarding_back))
                        }
                    },
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            StepIndicator(
                currentStep = step.ordinal,
                totalSteps = OnboardingStep.entries.size,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
            ) {
                when (step) {
                    OnboardingStep.Welcome -> WelcomeStep()
                    OnboardingStep.ServerUrl -> ServerUrlStep(viewModel)
                    OnboardingStep.VerifyServer -> VerifyStep(viewModel)
                    OnboardingStep.Login -> LoginStep(viewModel)
                    OnboardingStep.SelectApp -> SelectAppStep(viewModel)
                }
            }

            ActionBar(
                step = step,
                verifyPhase = verifyPhase,
                loginPhase = loginPhase,
                viewModel = viewModel,
                onComplete = onComplete,
                modifier = Modifier.padding(24.dp),
            )
        }
    }

    loginNotice?.let { presentation ->
        AlertDialog(
            onDismissRequest = viewModel::dismissLoginNotice,
            title = { Text(presentation.title) },
            text = {
                presentation.message?.takeIf { it.isNotBlank() }?.let { Text(it) }
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissLoginNotice) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }

    val loginUrl = authorizationUrl
    val host = callbackHost
    if (loginUrl != null && host != null) {
        Dialog(
            onDismissRequest = viewModel::cancelLogin,
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true),
        ) {
            BackHandler(onBack = viewModel::cancelLogin)
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.login_title)) },
                        navigationIcon = {
                            TextButton(onClick = viewModel::cancelLogin) {
                                Text(stringResource(R.string.cancel))
                            }
                        },
                    )
                },
            ) { innerPadding ->
                EneoLoginWebView(
                    authorizationUrl = loginUrl,
                    callbackHost = host,
                    onCallback = viewModel::completeLogin,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                )
            }
        }
    }
}

@Composable
private fun StepIndicator(currentStep: Int, totalSteps: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(totalSteps) { index ->
            Spacer(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .background(
                        color = if (index <= currentStep) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(999.dp),
                    ),
            )
        }
    }
}

@Composable
private fun WelcomeStep() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(
            Icons.Filled.Verified,
            contentDescription = null,
            modifier = Modifier
                .size(56.dp)
                .align(Alignment.CenterHorizontally),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(stringResource(R.string.onboarding_welcome_title), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.onboarding_app_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.onboarding_welcome_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.onboarding_local_storage), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ServerUrlStep(viewModel: OnboardingViewModel) {
    val draft by viewModel.serverUrlDraft.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.onboarding_connect_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.onboarding_connect_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = draft,
            onValueChange = viewModel::setServerUrlDraft,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.onboarding_server_label)) },
            placeholder = { Text(stringResource(R.string.onboarding_server_hint)) },
            singleLine = true,
        )
        Text(
            stringResource(R.string.onboarding_server_example),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VerifyStep(viewModel: OnboardingViewModel) {
    val phase by viewModel.verifyPhase.collectAsStateWithLifecycle()
    val host by viewModel.verifiedHost.collectAsStateWithLifecycle()
    val errorMessage by viewModel.verifyErrorMessage.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (phase) {
            VerifyPhase.Running -> {
                Text(stringResource(R.string.onboarding_checking_server), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.onboarding_checking_server), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            VerifyPhase.Success -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.onboarding_verified_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                host?.let {
                    Text(
                        it,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                            .padding(16.dp),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            VerifyPhase.Failure -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                    Text(stringResource(R.string.onboarding_verify_failed_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                Text(errorMessage ?: stringResource(R.string.onboarding_verify_failed_message), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            VerifyPhase.Idle -> Unit
        }
    }
}

@Composable
private fun LoginStep(viewModel: OnboardingViewModel) {
    val phase by viewModel.loginPhase.collectAsStateWithLifecycle()
    val statusText by viewModel.authService.statusText.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (phase) {
            LoginPhase.Prompt, LoginPhase.SigningIn -> {
                Text(stringResource(R.string.onboarding_login_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.onboarding_login_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.onboarding_login_footer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (phase == LoginPhase.SigningIn) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(statusText ?: stringResource(R.string.login_opening_short), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            LoginPhase.SignedIn -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.onboarding_signed_in_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                Text(stringResource(R.string.onboarding_signed_in_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SelectAppStep(viewModel: OnboardingViewModel) {
    val apps by viewModel.availableApps.collectAsStateWithLifecycle()
    val personalSpaceId by viewModel.personalSpaceId.collectAsStateWithLifecycle()
    val selectedAppId by viewModel.selectedAppId.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoadingApps.collectAsStateWithLifecycle()
    val error by viewModel.appLoadError.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.onboarding_select_app_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.onboarding_select_app_body), color = MaterialTheme.colorScheme.onSurfaceVariant)

        when {
            isLoading -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.onboarding_loading_apps), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error != null -> {
                Text(error?.message ?: error?.title.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                val grouped = remember(apps, personalSpaceId) { groupEneoApps(apps, personalSpaceId) }
                if (grouped.personal.isNotEmpty()) {
                    AppSection(
                        title = stringResource(R.string.onboarding_my_apps),
                        apps = grouped.personal,
                        isPersonalSection = true,
                        selectedAppId = selectedAppId,
                        onSelect = viewModel::selectApp,
                    )
                }
                if (grouped.shared.isNotEmpty()) {
                    AppSection(
                        title = stringResource(R.string.onboarding_shared_apps),
                        apps = grouped.shared,
                        isPersonalSection = false,
                        selectedAppId = selectedAppId,
                        onSelect = viewModel::selectApp,
                    )
                }
                if (grouped.personal.isEmpty() && grouped.shared.isEmpty()) {
                    Text(stringResource(R.string.error_no_apps_message), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun AppSection(
    title: String,
    apps: List<EneoApp>,
    isPersonalSection: Boolean,
    selectedAppId: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp)),
        ) {
            apps.forEachIndexed { index, app ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(app.id) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(app.pickerLabel(isPersonalSection), modifier = Modifier.weight(1f))
                    if (app.id == selectedAppId) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                if (index < apps.lastIndex) HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            }
        }
    }
}

@Composable
private fun ActionBar(
    step: OnboardingStep,
    verifyPhase: VerifyPhase,
    loginPhase: LoginPhase,
    viewModel: OnboardingViewModel,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (step) {
            OnboardingStep.Welcome -> {
                PrimaryButton(stringResource(R.string.onboarding_get_started), onClick = viewModel::goToServerUrl)
            }
            OnboardingStep.ServerUrl -> {
                val draft by viewModel.serverUrlDraft.collectAsStateWithLifecycle()
                PrimaryButton(
                    text = stringResource(R.string.onboarding_verify_server),
                    enabled = ApiSettingsStore.makeBaseUrl(draft) != null,
                    onClick = viewModel::goToVerifyServer,
                )
            }
            OnboardingStep.VerifyServer -> when (verifyPhase) {
                VerifyPhase.Success -> PrimaryButton(stringResource(R.string.onboarding_continue), onClick = viewModel::goToLogin)
                VerifyPhase.Failure -> {
                    PrimaryButton(stringResource(R.string.retry), onClick = viewModel::retryVerification)
                    OutlinedButton(onClick = viewModel::editServerAfterFailure, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.onboarding_change_server))
                    }
                }
                else -> Unit
            }
            OnboardingStep.Login -> when (loginPhase) {
                LoginPhase.Prompt -> PrimaryButton(stringResource(R.string.onboarding_login_button), onClick = viewModel::beginLogin)
                LoginPhase.SignedIn -> PrimaryButton(stringResource(R.string.onboarding_continue), onClick = viewModel::goToSelectApp)
                LoginPhase.SigningIn -> Unit
            }
            OnboardingStep.SelectApp -> {
                val isLoading by viewModel.isLoadingApps.collectAsStateWithLifecycle()
                val selected by viewModel.selectedAppId.collectAsStateWithLifecycle()
                PrimaryButton(
                    text = stringResource(R.string.onboarding_finish),
                    enabled = !isLoading,
                    onClick = {
                        viewModel.completeOnboarding()
                        onComplete()
                    },
                )
                if (selected.isBlank() && !isLoading) {
                    Text(
                        stringResource(R.string.onboarding_finish_later_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(text)
    }
}
