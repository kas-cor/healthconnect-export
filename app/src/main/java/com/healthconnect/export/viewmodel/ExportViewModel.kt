package com.healthconnect.export.viewmodel

import android.content.Context
import androidx.activity.result.ActivityResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.common.api.ApiException
import com.healthconnect.export.data.*
import com.healthconnect.export.repository.HealthConnectRepository
import com.healthconnect.export.repository.LocalExportRepository
import com.healthconnect.export.repository.GoogleDriveRepository
import com.healthconnect.export.repository.WebhookRepository
import com.healthconnect.export.repository.WebhookResult
import com.healthconnect.export.worker.DailyExportWorker
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class ExportUiState(
    val isLoading: Boolean = false,
    val exportProgress: String = "",
    val exportedFiles: List<File> = emptyList(),
    val driveStatus: DriveStatus = DriveStatus.NotConnected,
    val scheduleStatus: ScheduleStatus = ScheduleStatus.NotScheduled,
    val selectedTypes: Set<HealthDataType> = HealthDataType.values().toSet(),
    val startDate: LocalDate = LocalDate.now().minusDays(7),
    val endDate: LocalDate = LocalDate.now().minusDays(1),
    val frequency: ExportFrequency = ExportFrequency.MANUAL,
    val autoSyncDrive: Boolean = true,
    val webhookUrl: String = "",
    val autoSendWebhook: Boolean = false,
    val message: String? = null
)

sealed class DriveStatus {
    object NotConnected : DriveStatus()
    object Connected : DriveStatus()
    object Syncing : DriveStatus()
    data class Synced(val filesCount: Int) : DriveStatus()
    data class Error(val error: String) : DriveStatus()
}

sealed class ScheduleStatus {
    object NotScheduled : ScheduleStatus()
    data class Scheduled(val nextRun: String) : ScheduleStatus()
    object Running : ScheduleStatus()
}

class ExportViewModel(private val context: Context) : ViewModel() {

    private val healthRepo = HealthConnectRepository(context)
    private val localRepo = LocalExportRepository(context)
    private val driveRepo = GoogleDriveRepository(context)
    private val webhookRepo = WebhookRepository()

    val googleSignInClient: GoogleSignInClient =
        GoogleSignIn.getClient(context, driveRepo.getSignInOptions())

    private val _uiState = MutableStateFlow(ExportUiState())
    val uiState = _uiState.asStateFlow()

    // Набор разрешений для запроса Health Connect (заполняется перед экспортом)
    var pendingPermissions: Set<String>? = null
        private set

    init {
        refreshDriveStatus()
        refreshLocalFiles()
    }

    /**
     * Обработка результата Google Sign-In
     */
    fun handleSignInResult(result: ActivityResult) {
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            val account = task.getResult(ApiException::class.java)
            if (account != null) {
                _uiState.update {
                    it.copy(
                        driveStatus = DriveStatus.Connected,
                        message = "Google аккаунт подключён: ${account.email}"
                    )
                }
                refreshDriveStatus()
            }
        } catch (e: ApiException) {
            _uiState.update {
                it.copy(
                    driveStatus = DriveStatus.Error("Ошибка входа: ${e.statusCode}"),
                    message = "Не удалось войти: ${e.statusCode}"
                )
            }
        }
    }

    /**
     * Выход из Google аккаунта
     */
    fun signOut() {
        googleSignInClient.signOut().addOnCompleteListener {
            _uiState.update {
                it.copy(
                    driveStatus = DriveStatus.NotConnected,
                    message = "Google аккаунт отключён"
                )
            }
        }
    }

    fun selectTypes(types: Set<HealthDataType>) {
        _uiState.update { it.copy(selectedTypes = types) }
    }

    fun setDateRange(start: LocalDate, end: LocalDate) {
        _uiState.update { it.copy(startDate = start, endDate = end) }
    }

    fun setFrequency(freq: ExportFrequency) {
        _uiState.update { it.copy(frequency = freq) }
    }

    fun setAutoSyncDrive(enabled: Boolean) {
        _uiState.update { it.copy(autoSyncDrive = enabled) }
    }

    fun setWebhookUrl(url: String) {
        _uiState.update { it.copy(webhookUrl = url) }
    }

    fun setAutoSendWebhook(enabled: Boolean) {
        _uiState.update { it.copy(autoSendWebhook = enabled) }
    }

    fun scheduleExport() {
        val config = ExportConfig(
            enabledTypes = _uiState.value.selectedTypes,
            frequency = _uiState.value.frequency,
            autoSyncDrive = _uiState.value.autoSyncDrive
        )
        DailyExportWorker.schedule(context, config)
        _uiState.update {
            it.copy(
                scheduleStatus = ScheduleStatus.Scheduled("Следующий запуск: через 24ч"),
                message = "Автоэкспорт настроен: ${_uiState.value.frequency.displayName}"
            )
        }
    }

    fun cancelSchedule() {
        DailyExportWorker.cancel(context)
        _uiState.update {
            it.copy(
                scheduleStatus = ScheduleStatus.NotScheduled,
                message = "Автоэкспорт отменён"
            )
        }
    }

    fun exportNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, exportProgress = "Проверка разрешений...") }

            val state = _uiState.value
            val config = ExportConfig(
                enabledTypes = HealthDataType.values().toSet(),
                frequency = com.healthconnect.export.data.ExportFrequency.DAILY,
                autoSyncDrive = true
            )

            // Проверяем разрешения Health Connect
            val hasPermissions = healthRepo.checkPermissions(state.selectedTypes)
            if (!hasPermissions) {
                val permissions = healthRepo.getPermissionsForTypes(state.selectedTypes)
                pendingPermissions = permissions
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        message = "Требуются разрешения Health Connect. Подтвердите в открывшемся окне."
                    )
                }
                return@launch
            }

            _uiState.update { it.copy(exportProgress = "Чтение данных...") }

            try {
                val records = healthRepo.readPeriod(state.startDate, state.endDate, state.selectedTypes)

                _uiState.update { it.copy(exportProgress = "Сохранение ${records.size} дней...") }

                val files = localRepo.saveRecords(records, config)

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        exportProgress = "Сохранено ${files.size} файлов",
                        exportedFiles = files,
                        message = "Экспорт завершён: ${files.size} файлов"
                    )
                }

                // Try Drive sync if enabled
                if (state.autoSyncDrive && driveRepo.isSignedIn()) {
                    syncToDrive(files)
                }

                // Try webhook if enabled
                if (state.autoSendWebhook && state.webhookUrl.isNotBlank()) {
                    sendToWebhook(state.webhookUrl, records)
                }

            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        exportProgress = "Ошибка: ${e.message}",
                        message = "Ошибка экспорта: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Вызывается после возврата из Health Connect permission screen
     */
    fun onPermissionsResult(grantedPermissions: Set<String>) {
        pendingPermissions = null
        viewModelScope.launch {
            val state = _uiState.value
            val required = healthRepo.getPermissionsForTypes(state.selectedTypes)
            if (grantedPermissions.containsAll(required)) {
                _uiState.update { it.copy(message = "Разрешения получены. Начинаю экспорт...") }
                exportNow()
            } else {
                _uiState.update {
                    it.copy(message = "Разрешения Health Connect не предоставлены. Экспорт невозможен.")
                }
            }
        }
    }

    fun syncToDrive(files: List<File> = _uiState.value.exportedFiles) {
        if (!driveRepo.isSignedIn()) {
            _uiState.update { it.copy(driveStatus = DriveStatus.NotConnected, message = "Google Drive не подключён") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(driveStatus = DriveStatus.Syncing) }
            try {
                val results = driveRepo.syncFiles(files)
                val syncedCount = results.count { it != null }
                _uiState.update {
                    it.copy(
                        driveStatus = DriveStatus.Synced(syncedCount),
                        message = "Синхронизировано с Drive: $syncedCount файлов"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(driveStatus = DriveStatus.Error(e.message ?: "Неизвестная ошибка"))
                }
            }
        }
    }

    fun refreshDriveStatus() {
        if (driveRepo.isSignedIn()) {
            _uiState.update { it.copy(driveStatus = DriveStatus.Connected) }
            viewModelScope.launch {
                val driveFiles = driveRepo.listDriveFiles()
                _uiState.update { it.copy(driveStatus = DriveStatus.Synced(driveFiles.size)) }
            }
        }
    }

    fun refreshLocalFiles() {
        val config = ExportConfig(
            enabledTypes = _uiState.value.selectedTypes,
            frequency = _uiState.value.frequency,
            autoSyncDrive = _uiState.value.autoSyncDrive
        )
        val files = localRepo.listExportedFiles(config).map { it.second }
        _uiState.update { it.copy(exportedFiles = files) }
    }

    /**
     * Отправляет записи на webhook URL
     */
    private fun sendToWebhook(url: String, records: List<DailyHealthRecord>) {
        viewModelScope.launch {
            _uiState.update { it.copy(exportProgress = "Отправка на webhook...") }
            when (val result = webhookRepo.sendRecords(url, records)) {
                is WebhookResult.Success -> {
                    _uiState.update {
                        it.copy(
                            message = "Webhook: успешно (${result.statusCode})"
                        )
                    }
                }
                is WebhookResult.Error -> {
                    _uiState.update {
                        it.copy(
                            message = "Webhook: ошибка ${result.statusCode} — ${result.message}"
                        )
                    }
                }
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
