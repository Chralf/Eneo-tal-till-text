package se.iteca.eneotaltilltext.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import se.iteca.eneotaltilltext.R
import se.iteca.eneotaltilltext.network.EneoApp
import se.iteca.eneotaltilltext.network.GroupedEneoApps
import se.iteca.eneotaltilltext.network.groupEneoApps
import se.iteca.eneotaltilltext.network.pickerLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EneoAppPickerDialog(
    apps: List<EneoApp>,
    selectedAppId: String,
    personalSpaceId: String? = null,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.detail_app_picker_title)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.close),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            EneoAppPickerContent(
                apps = apps,
                selectedAppId = selectedAppId,
                personalSpaceId = personalSpaceId,
                onSelect = {
                    onSelect(it)
                    onDismiss()
                },
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
fun EneoAppPickerContent(
    apps: List<EneoApp>,
    selectedAppId: String,
    personalSpaceId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var searchText by remember { mutableStateOf("") }
    val grouped = remember(apps, personalSpaceId, searchText) {
        val query = searchText.trim()
        val all = groupEneoApps(apps, personalSpaceId)
        if (query.isEmpty()) {
            all
        } else {
            val filter: (EneoApp) -> Boolean = { app ->
                app.displayName.contains(query, ignoreCase = true)
            }
            GroupedEneoApps(
                personal = all.personal.filter(filter),
                shared = all.shared.filter(filter),
            )
        }
    }
    val hasApps = grouped.personal.isNotEmpty() || grouped.shared.isNotEmpty()

    Column(modifier) {
        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.detail_app_search)) },
            singleLine = true,
        )
        if (!hasApps) {
            Text(
                stringResource(R.string.detail_search_no_apps),
                modifier = Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn {
                if (grouped.personal.isNotEmpty()) {
                    item(key = "header-personal") {
                        PickerSectionHeader(stringResource(R.string.onboarding_my_apps))
                    }
                    items(grouped.personal, key = { "p-${it.id}" }) { app ->
                        AppPickerRow(
                            label = app.pickerLabel(isPersonalSection = true),
                            selected = app.id == selectedAppId,
                            onClick = { onSelect(app.id) },
                        )
                    }
                }
                if (grouped.shared.isNotEmpty()) {
                    item(key = "header-shared") {
                        PickerSectionHeader(stringResource(R.string.onboarding_shared_apps))
                    }
                    items(grouped.shared, key = { "s-${it.id}" }) { app ->
                        AppPickerRow(
                            label = app.pickerLabel(isPersonalSection = false),
                            selected = app.id == selectedAppId,
                            onClick = { onSelect(app.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerSectionHeader(title: String) {
    Text(
        title,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AppPickerRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
    HorizontalDivider()
}
