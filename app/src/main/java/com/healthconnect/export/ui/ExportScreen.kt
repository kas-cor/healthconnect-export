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
    uiState.message?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    // Запускаем запрос Health Connect permissions, если есть ожидающий набор
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
                    autoSendWebhook = uiState.autoSendWebhook,
                    onUrlChange = { viewModel.setWebhookUrl(it) },
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
                    Text("Автоматически синхронизировать с Google Drive")
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
                        Text("Экспортировать ${uiState.selectedTypes.size} типов данных")
                    }
                }
            }

            // Exported Files List
            if (uiState.exportedFiles.isNotEmpty()) {
                item {
                    Text(
                        "Экспортированные файлы (${uiState.exportedFiles.size}):",
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
                                Text("${file.length() / 1024} KB", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

@Composable
fun DriveStatusCard(status: DriveStatus, onSync: () -> Unit, onSignInClick: () -> Unit, onSignOutClick: () -> Unit) {
    val (icon, title, color) = when (status) {
        is DriveStatus.NotConnected ->
            Triple(Icons.Default.CloudOff, "Google Drive не подключён", MaterialTheme.colorScheme.error)
        is DriveStatus.Connected ->
            Triple(Icons.Default.Cloud, "Google Drive подключён", MaterialTheme.colorScheme.primary)
        is DriveStatus.Syncing ->
            Triple(Icons.Default.Refresh, "Синхронизация...", MaterialTheme.colorScheme.tertiary)
        is DriveStatus.Synced ->
            Triple(Icons.Default.Cloud, "Синхронизировано: ${status.filesCount} файлов", MaterialTheme.colorScheme.primary)
        is DriveStatus.Error ->
            Triple(Icons.Default.CloudOff, "Ошибка: ${status.error}", MaterialTheme.colorScheme.error)
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
                        Text("Войти в Google")
                    }
                }
                is DriveStatus.Connected, is DriveStatus.Synced -> {
                    TextButton(onClick = onSync) {
                        Text("Синхронизировать")
                    }
                    TextButton(onClick = onSignOutClick) {
                        Text("Выйти")
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
                Text(
                    "Автоматический экспорт",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Frequency selector
            ExportFrequency.values().forEach { freq ->
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
                            Text("Включить расписание")
                        }
                    }
                }
                is ScheduleStatus.Scheduled -> {
                    Text(scheduleStatus.nextRun, style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                        Text("Отключить")
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
            Text("Типы данных", style = MaterialTheme.typography.titleMedium)
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
            Text("Период экспорта", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            // Quick period buttons
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    onStartDateChange(LocalDate.now().minusDays(7))
                    onEndDateChange(LocalDate.now().minusDays(1))
                }) {
                    Text("7 дней")
                }
                OutlinedButton(onClick = {
                    onStartDateChange(LocalDate.now().minusDays(30))
                    onEndDateChange(LocalDate.now().minusDays(1))
                }) {
                    Text("30 дней")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Date pickers
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                DatePickerButton(
                    label = "С",
                    date = startDate,
                    onDateSelected = onStartDateChange
                )
                DatePickerButton(
                    label = "По",
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
    autoSendWebhook: Boolean,
    onUrlChange: (String) -> Unit,
    onToggle: (Boolean) -> Unit
) {
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
                label = { Text("URL вебхука") },
                placeholder = { Text("https://example.com/api/health-data") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
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
                    if (webhookUrl.isBlank()) "Укажите URL для отправки"
                    else "Отправлять JSON на вебхук после экспорта"
                )
            }
        }
    }
}
