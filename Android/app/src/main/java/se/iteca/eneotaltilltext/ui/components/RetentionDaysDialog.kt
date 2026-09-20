package se.iteca.eneotaltilltext.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import se.iteca.eneotaltilltext.R
import se.iteca.eneotaltilltext.data.RecordingRetention

@Composable
fun RetentionDaysDialog(
    selectedDays: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_retention_label)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                RecordingRetention.SELECTABLE_DAYS.forEachIndexed { index, days ->
                    if (index > 0) HorizontalDivider()
                    RetentionOptionRow(
                        label = if (days == 1) {
                            stringResource(R.string.settings_retention_day_one)
                        } else {
                            stringResource(R.string.settings_retention_days, days)
                        },
                        selected = selectedDays == days,
                        onClick = { onSelect(days) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun RetentionOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        style = if (selected) {
            MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
        } else {
            MaterialTheme.typography.bodyLarge
        },
    )
}

@Composable
fun retentionDaysLabel(days: Int): String {
    val value = RecordingRetention.clamped(days)
    return if (value == 1) {
        stringResource(R.string.settings_retention_day_one)
    } else {
        stringResource(R.string.settings_retention_days, value)
    }
}
