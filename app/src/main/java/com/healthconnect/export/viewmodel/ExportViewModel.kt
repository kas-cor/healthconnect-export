package com.healthconnect.export.viewmodel

import android.app.Application
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.lifecycle.AndroidViewModel
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
    val selectedTypes: Set<HealthDataType> = HealthDataType.entries.toSet(),
    val startDate: LocalDate = LocalDate.now().minusDays(7),
    val endDate: LocalDate = LocalDate.now().minusDays(1),
    val frequency: ExportFrequency = ExportFrequency.DAILY,
    val autoSyncDrive: Boolean = true,
    val webhookUrl: String = "",
    val webhookAuthToken: String = "",
    val autoSendWebhook: Boolean = false,
    val webhookUrlError: String? = null,
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

class ExportViewModel(application: Application) : AndroidViewModel(application) {

    private val healthRepo = HealthConnectRepository(getApplication())
    private val localRepo = LocalExportRepository(getApplication())
    private val driveRepo = GoogleDriveRepository(getApplication())
    private val webhookRepo = WebhookRepository()

    val googleSignInClient: GoogleSignInClient =
        GoogleSignIn.getClient(getApplication(), driveRepo.getSignInOptions())

    private val _uiState = MutableStateFlow(ExportUiState())
    val uiState = _uiState.asStateFlow()

    // Набор разрешений для запроса Health Connect
    var pendingPermissions: Set<String>? = null
        private set

    init {
        refreshDriveStatus()
        refreshLocalFiles()
        scheduleExport()
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
        val error = if (url.isNotBlank() && !webhookRepo.isValidWebhookUrl(url)) {
            "Invalid URL format. Must be http:// or https://"
        } else null
        _uiState.update { it.copy(webhookUrl = url, webhookUrlError = error) }
    }

    fun setWebhookAuthToken(token: String) {
        _uiState.update { it.copy(webhookAuthToken = token) }
    }

    fun setAutoSendWebhook(enabled: Boolean) {
        _uiState.update { it.copy(autoSendWebhook = enabled) }
    }

    fun scheduleExport() {
        val state = _uiState.value
        val config = ExportConfig(
            enabledTypes = state.selectedTypes,
            frequency = state.frequency,
            autoSyncDrive = state.autoSyncDrive,
            webhookUrl = state.webhookUrl,
            webhookAuthToken = state.webhookAuthToken,
            autoSendWebhook = state.autoSendWebhook
        )
        DailyExportWorker.schedule(getApplication(), config)
        _uiState.update {
            it.copy(
                scheduleStatus = ScheduleStatus.Scheduled("Next run: in 24h"),
                message = "Scheduled export set: ${config.frequency.displayName}"
            )
        }
    }

    fun cancelSchedule() {
        DailyExportWorker.cancel(getApplication())
        _uiState.update {
            it.copy(
                scheduleStatus = ScheduleStatus.NotScheduled,
                message = "Scheduled export cancelled"
            )
        }
    }

    fun exportNow() {
        Log.d("ExportViewModel", "exportNow() called")
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, exportProgress = "Проверка разрешений...") }

            val state = _uiState.value

            // Проверяем доступность Health Connect на устройстве
            val healthAvailable = healthRepo.isHealthConnectAvailable()
            Log.d("ExportViewModel", "HealthConnect available: $healthAvailable")
            if (!healthAvailable) {
                if (healthRepo.isHealthConnectInstalled()) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            message = "Не удалось подключиться к Health Connect. Попробуйте перезапустить приложение."
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            message = "Health Connect не установлен. Установите приложение Health Connect из Google Play."
                        )
                    }
                }
                return@launch
            }

            // Проверяем разрешения Health Connect
            val hasPermissions = healthRepo.checkPermissions(state.selectedTypes)
            Log.d("ExportViewModel", "Has permissions: $hasPermissions")
            if (!hasPermissions) {
                val permissions = healthRepo.getPermissionsForTypes(state.selectedTypes)
                Log.d("ExportViewModel", "Requesting permissions: $permissions")
                pendingPermissions = permissions
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        message = "Требуются разрешения Health Connect. Подтвердите в открывшемся окне."
                    )
                }
                return@launch
            }

            val config = ExportConfig(
                enabledTypes = state.selectedTypes,
                frequency = state.frequency,
                autoSyncDrive = state.autoSyncDrive
            )

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
                    sendToWebhook(state.webhookUrl, state.webhookAuthToken, records)
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
     * @param grantedPermissions набор разрешений, предоставленных пользователем
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
                val missing = required - grantedPermissions
                val missingNames = missing.mapNotNull { perm ->
                    when {
                        perm.contains("READ_STEPS") -> "шаги"
                        perm.contains("READ_HEART_RATE") -> "пульс"
                        perm.contains("READ_SLEEP") -> "сон"
                        perm.contains("READ_TOTAL_CALORIES_BURNED") -> "калории"
                        perm.contains("READ_DISTANCE") -> "дистанция"
                        perm.contains("READ_FLOORS_CLIMBED") -> "этажи"
                        perm.contains("READ_ACTIVE_CALORIES_BURNED") -> "активные калории"
                        perm.contains("READ_WEIGHT") -> "вес"
                        perm.contains("READ_BODY_FAT") -> "жир"
                        perm.contains("READ_BLOOD_PRESSURE") -> "давление"
                        perm.contains("READ_BLOOD_GLUCOSE") -> "глюкоза"
                        perm.contains("READ_OXYGEN_SATURATION") -> "сатурация"
                        perm.contains("READ_BODY_TEMPERATURE") -> "температура"
                        perm.contains("READ_RESPIRATORY_RATE") -> "дыхание"
                        perm.contains("READ_HYDRATION") -> "гидратация"
                        perm.contains("READ_RESTING_HEART_RATE") -> "пульс покоя"
                        perm.contains("READ_EXERCISE") -> "тренировки"
                        perm.contains("READ_NUTRITION") -> "питание"
                        perm.contains("READ_MENSTRUATION") -> "цикл"
                        perm.contains("READ_HEART_RATE_VARIABILITY_RMSSD") -> "ВСР"
                        perm.contains("READ_SPEED") -> "скорость ходьбы/бега"
                        else -> null
                    }
                }
                _uiState.update {
                    it.copy(
                        message = "Разрешения Health Connect не предоставлены для: ${missingNames.joinToString(", ")}. " +
                                "Экспорт невозможен. Проверьте настройки Health Connect."
                    )
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
    private fun sendToWebhook(url: String, authToken: String, records: List<DailyHealthRecord>) {
        viewModelScope.launch {
            _uiState.update { it.copy(exportProgress = "Отправка на webhook...") }
            when (val result = webhookRepo.sendRecords(url, records, authToken)) {
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
