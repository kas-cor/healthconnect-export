package com.healthconnect.export.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.healthconnect.export.R

/**
 * Dialog showing the release notes ("What's new") for an available app update.
 *
 * @param version The latest version (e.g. "1.7") to show in the title.
 * @param releaseNotes The release notes body text fetched from GitHub.
 * @param onDismiss Callback invoked when the dialog is dismissed.
 * @param onDownload Optional callback for the "Download" action. When null,
 * only a Close button is shown.
 */
@Composable
fun ReleaseNotesDialog(
    version: String,
    releaseNotes: String,
    onDismiss: () -> Unit,
    onDownload: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Campaign,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                text = stringResource(R.string.whats_new_title, version),
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Text(
                text = releaseNotes,
                style = MaterialTheme.typography.bodyMedium,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            if (onDownload != null) {
                TextButton(onClick = onDownload) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.update_download))
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.close))
                }
            }
        },
        dismissButton = {
            if (onDownload != null) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.close))
                }
            }
        },
    )
}
