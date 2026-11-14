package se.iteca.eneotaltilltext.ui.info

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import se.iteca.eneotaltilltext.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoScreen(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val supportWebsiteUrl = stringResource(R.string.settings_support_website_url)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.info_title)) },
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
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TipBlock(
                title = stringResource(R.string.info_tip_before_title),
                body = stringResource(R.string.info_tip_before_body),
            )
            TipBlock(
                title = stringResource(R.string.info_tip_audio_title),
                body = stringResource(R.string.info_tip_audio_body),
            )
            TipBlock(
                title = stringResource(R.string.info_tip_review_title),
                body = stringResource(R.string.info_tip_review_body),
            )
            TipBlock(
                title = stringResource(R.string.info_tip_public_record_title),
                body = stringResource(R.string.info_tip_public_record_body),
            )
            TipBlock(
                title = stringResource(R.string.info_tip_about_title),
                body = stringResource(R.string.info_tip_about_body),
            )

            Spacer(Modifier.padding(top = 4.dp))
            ContactBlock(
                title = stringResource(R.string.info_section_contact),
                lead = stringResource(R.string.settings_support_free),
            )
            TextButton(
                onClick = {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_SENDTO,
                            Uri.parse(
                                "mailto:christian@iteca.se?subject=" +
                                    Uri.encode("Eneo tal-till-text"),
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
        }
    }
}

@Composable
private fun TipBlock(title: String, body: String) {
    InfoCard {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            body.split("\n\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { paragraph ->
                    Text(
                        paragraph,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
        }
    }
}

@Composable
private fun ContactBlock(title: String, lead: String) {
    InfoCard {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            lead,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerLow,
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}
