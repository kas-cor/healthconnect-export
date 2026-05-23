package com.healthconnect.export.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.healthconnect.export.data.ExportFrequency
import com.healthconnect.export.data.HealthDataType
import com.healthconnect.export.viewmodel.DriveStatus
import com.healthconnect.export.viewmodel.ExportViewModel
import com.healthconnect.export.viewmodel.ScheduleStatus
import java.time.LocalDate
import java.util.Locale
import java.time.format.DateTimeFormatter
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    viewModel: ExportViewModel,
    onSignInClick: () -> Unit,
    onRequestHealthPermissions: (Set<String>) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Показываем сообщения через Snackbar
    LaunchedEffect(uiState.message) {
        uiState.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    // Запускаем запрос Health Connect permissions, если есть ожидающий набор разрешений
    LaunchedEffect(viewModel.pendingPermissions) {
        viewModel.pendingPermissions?.let { permissions ->
            onRequestHealthPermissions(permissions)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("HealthConnect Export") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Drive Status Card
            item {
                DriveStatusCard(
                    status = uiState.driveStatus,
                    onSync = { viewModel.syncToDrive() },
                    onSignInClick = onSignInClick,
                    onSignOutClick = { viewModel.signOut() }
                )
            }

            // Webhook Card
            item {
                WebhookCard(
                    webhookUrl = uiState.webhookUrl,
                    webhookUrlError = uiState.webhookUrlError,
                    webhookAuthToken = uiState.webhookAuthToken,
                    autoSendWebhook = uiState.autoSendWebhook,
                    onUrlChange = { viewModel.setWebhookUrl(it) },
                    onTokenChange = { viewModel.setWebhookAuthToken(it) },
                    onToggle = { viewModel.setAutoSendWebhook(it) }
                )
            }

            // Schedule Settings
            item {
                ScheduleCard(
                    frequency = uiState.frequency,
                    scheduleStatus = uiState.scheduleStatus,
                    onFrequencyChange = { viewModel.setFrequency(it) },
                    onSchedule = { viewModel.scheduleExport() },
                    onCancel = { viewModel.cancelSchedule() }
                )
            }

            // Data Type Selection
            item {
                DataTypeCard(
                    selectedTypes = uiState.selectedTypes,
                    onTypeToggle = { type ->
                        val newTypes = if (uiState.selectedTypes.contains(type)) {
                            uiState.selectedTypes - type
                        } else {
                            uiState.selectedTypes + type
                        }
                        viewModel.selectTypes(newTypes)
                    }
                )
            }

            // Date Range Selection
            item {
                DateRangeCard(
                    startDate = uiState.startDate,
                    endDate = uiState.endDate,
                    onStartDateChange = { viewModel.setDateRange(it, uiState.endDate) },
                    onEndDateChange = { viewModel.setDateRange(uiState.startDate, it) }
                )
            }

            // Auto-sync Drive checkbox
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = uiState.autoSyncDrive,
                        onCheckedChange = { viewModel.setAutoSyncDrive(it) }
                    )
                    Text("Auto-sync with Google Drive")
                }
            }

            // Export Button
            item {
                Button(
                    onClick = { viewModel.exportNow() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isLoading
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(uiState.exportProgress)
                    } else {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Export ${uiState.selectedTypes.size} data types")
                    }
                }
            }

            // Exported Files List
            if (uiState.exportedFiles.isNotEmpty()) {
                item {
                    Text(
                        "Exported files (${uiState.exportedFiles.size}):",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                items(uiState.exportedFiles) { file ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(12.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(file.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    formatFileSize(file.length()),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}

@Composable
fun DriveStatusCard(status: DriveStatus, onSync: () -> Unit, onSignInClick: () -> Unit, onSignOutClick: () -> Unit) {
    val (icon, title, color) = when (status) {
        is DriveStatus.NotConnected ->
            Triple(Icons.Default.CloudOff, "Google Drive not connected", MaterialTheme.colorScheme.error)
        is DriveStatus.Connected ->
            Triple(Icons.Default.Cloud, "Google Drive connected", MaterialTheme.colorScheme.primary)
        is DriveStatus.Syncing ->
            Triple(Icons.Default.Refresh, "Syncing...", MaterialTheme.colorScheme.tertiary)
        is DriveStatus.Synced ->
            Triple(Icons.Default.Cloud, "Synced: ${status.filesCount} files", MaterialTheme.colorScheme.primary)
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
                        Text("Sign in to Google")
                    }
                }
                is DriveStatus.Connected, is DriveStatus.Synced -> {
                    TextButton(onClick = onSync) {
                        Text("Sync")
                    }
                    TextButton(onClick = onSignOutClick) {
                        Text("Sign out")
                    }
                }
                is DriveStatus.Syncing -> {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
fun ScheduleCard(
    frequency: ExportFrequency,
    scheduleStatus: ScheduleStatus,
    onFrequencyChange: (ExportFrequency) -> Unit,
    onSchedule: () -> Unit,
    onCancel: () -> Unit
) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Schedule, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                    Text("Scheduled Export",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Frequency selector
            ExportFrequency.entries.forEach { freq ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    RadioButton(
                        selected = frequency == freq,
                        onClick = { onFrequencyChange(freq) }
                    )
                    Text(freq.displayName)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Schedule status and actions
            when (scheduleStatus) {
                is ScheduleStatus.NotScheduled -> {
                    if (frequency != ExportFrequency.MANUAL) {
                        Button(onClick = onSchedule, modifier = Modifier.fillMaxWidth()) {
                            Text("Enable Schedule")
                        }
                    }
                }
                is ScheduleStatus.Scheduled -> {
                    Text(scheduleStatus.nextRun, style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                            Text("Disable")
                    }
                }
                is ScheduleStatus.Running -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
fun DataTypeCard(
    selectedTypes: Set<HealthDataType>,
    onTypeToggle: (HealthDataType) -> Unit
) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
                    Text("Data Types", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            HealthDataType.entries.forEach { type ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = selectedTypes.contains(type),
                        onCheckedChange = { onTypeToggle(type) }
                    )
                    Text(type.displayName)
                }
            }
        }
    }
}

@Composable
fun DateRangeCard(
    startDate: LocalDate,
    endDate: LocalDate,
    onStartDateChange: (LocalDate) -> Unit,
    onEndDateChange: (LocalDate) -> Unit
) {
    val context = LocalContext.current

    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Export Period", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            // Quick period buttons
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    onStartDateChange(LocalDate.now().minusDays(7))
                    onEndDateChange(LocalDate.now().minusDays(1))
                }) {
                    Text("7 days")
                }
                OutlinedButton(onClick = {
                    onStartDateChange(LocalDate.now().minusDays(30))
                    onEndDateChange(LocalDate.now().minusDays(1))
                }) {
                    Text("30 days")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Date pickers
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                DatePickerButton(
                    label = "From",
                    date = startDate,
                    onDateSelected = onStartDateChange
                )
                DatePickerButton(
                    label = "To",
                    date = endDate,
                    onDateSelected = onEndDateChange
                )
            }
        }
    }
}

@Composable
fun DatePickerButton(label: String, date: LocalDate, onDateSelected: (LocalDate) -> Unit) {
    val context = LocalContext.current

    OutlinedButton(onClick = {
        val calendar = Calendar.getInstance()
        calendar.set(date.year, date.monthValue - 1, date.dayOfMonth)
        DatePickerDialog(
            context,
            { _, year, month, day ->
                onDateSelected(LocalDate.of(year, month + 1, day))
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }) {
        Text("$label: ${date.format(DateTimeFormatter.ISO_LOCAL_DATE)}")
    }
}

@Composable
fun WebhookCard(
    webhookUrl: String,
    webhookUrlError: String?,
    webhookAuthToken: String,
    autoSendWebhook: Boolean,
    onUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onToggle: (Boolean) -> Unit
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
                    "Webhook",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = webhookUrl,
                onValueChange = onUrlChange,
                label = { Text("Webhook URL") },
                placeholder = { Text("https://example.com/api/health-data") },
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
                label = { Text("Bearer Token (optional)") },
                placeholder = { Text("sk-...") },
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
                    if (webhookUrl.isBlank()) "Enter a webhook URL first"
                    else "Send JSON to webhook after export"
                )
            }
        }
    }
}
