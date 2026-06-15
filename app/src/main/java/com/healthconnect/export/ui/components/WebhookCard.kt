package com.healthconnect.export.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Http
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.healthconnect.export.R

@Composable
fun WebhookCard(
    webhookUrl: String,
    webhookUrlError: String?,
    webhookAuthToken: String,
    autoSendWebhook: Boolean,
    isTestingWebhook: Boolean = false,
    onUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onToggle: (Boolean) -> Unit,
    onTestClick: () -> Unit = {},
    onCancelTestClick: () -> Unit = {}
) {
    val urlHasError = webhookUrl.isNotBlank() && webhookUrlError != null

    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Http, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.webhook),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = webhookUrl,
                onValueChange = onUrlChange,
                label = { Text(stringResource(R.string.webhook_url_label)) },
                placeholder = { Text(stringResource(R.string.webhook_url_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = urlHasError,
                supportingText = if (urlHasError) {
                    { Text(webhookUrlError ?: "", color = MaterialTheme.colorScheme.error) }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = webhookAuthToken,
                onValueChange = onTokenChange,
                label = { Text(stringResource(R.string.bearer_token_label)) },
                placeholder = { Text(stringResource(R.string.bearer_token_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = autoSendWebhook,
                    onCheckedChange = onToggle,
                    enabled = webhookUrl.isNotBlank()
                )
                Text(
                    if (webhookUrl.isBlank()) stringResource(R.string.enter_url_first)
                    else stringResource(R.string.send_json)
                )
            }

            // Test webhook button / Cancel test button
            if (isTestingWebhook) {
                OutlinedButton(
                    onClick = onCancelTestClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.cancel_export))
                }
            } else {
                OutlinedButton(
                    onClick = onTestClick,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = webhookUrl.isNotBlank() && webhookUrlError == null
                ) {
                    Icon(
                        imageVector = Icons.Default.Http,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.webhook_test))
                }
            }
        }
    }
}
