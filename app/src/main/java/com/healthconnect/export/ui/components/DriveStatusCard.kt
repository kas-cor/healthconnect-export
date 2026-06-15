package com.healthconnect.export.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.healthconnect.export.R
import com.healthconnect.export.viewmodel.DriveStatus

@Composable
fun DriveStatusCard(
    status: DriveStatus,
    onSync: () -> Unit,
    onSignInClick: () -> Unit,
    onSignOutClick: () -> Unit
) {
    val (icon, title, color) = when (status) {
        is DriveStatus.NotConnected ->
            Triple(Icons.Default.CloudOff, "Drive not connected", MaterialTheme.colorScheme.error)
        is DriveStatus.Connected ->
            Triple(Icons.Default.Cloud, "Drive connected", MaterialTheme.colorScheme.primary)
        is DriveStatus.Syncing ->
            Triple(Icons.Default.Refresh, "Syncing...", MaterialTheme.colorScheme.tertiary)
        is DriveStatus.Synced ->
            Triple(Icons.Default.Cloud, "Synced (${status.filesCount} files)", MaterialTheme.colorScheme.primary)
        is DriveStatus.Error ->
            Triple(Icons.Default.CloudOff, "Error: ${status.error}", MaterialTheme.colorScheme.error)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = color)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                title,
                modifier = Modifier.weight(1f),
                color = color
            )
            when (status) {
                is DriveStatus.NotConnected, is DriveStatus.Error -> {
                    TextButton(onClick = onSignInClick) {
                        Text("Sign In")
                    }
                }
                is DriveStatus.Connected, is DriveStatus.Synced -> {
                    TextButton(onClick = onSync) {
                        Text("Sync Now")
                    }
                    TextButton(onClick = onSignOutClick) {
                        Text("Sign Out")
                    }
                }
                is DriveStatus.Syncing -> {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}
