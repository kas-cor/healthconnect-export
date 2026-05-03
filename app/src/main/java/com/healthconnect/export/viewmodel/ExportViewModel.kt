package com.healthconnect.export.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthconnect.export.data.*
import com.healthconnect.export.repository.HealthConnectRepository
import com.healthconnect.export.repository.LocalExportRepository
import com.healthconnect.export.repository.GoogleDriveRepository
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

    private val _uiState = MutableStateFlow(ExportUiState())
    val uiState = _uiState.asStateFlow()

    init {
        refreshDriveStatus()
        refreshLocalFiles()
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
                message = "Автоэкспорт настроен: ${freq.displayName}"
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
            _uiState.update { it.copy(isLoading = true, exportProgress = "Чтение данных...") }

            val state = _uiState.value
            val config = ExportConfig(
                enabledTypes = HealthDataType.values().toSet(),
                frequency = com.healthconnect.export.data.ExportFrequency.DAILY,
                autoSyncDrive = true
            )

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

    fun syncToDrive(files: List<File> = _uiState.value.exportedFiles) {
        if (!driveRepo.isSignedIn()) {
            _uiState.update { it.copy(driveStatus = DriveStatus.NotConnected, message = "Google Drive не подключён") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(driveStatus = DriveStatus.Running) }
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

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
