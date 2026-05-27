package com.healthconnect.export.usecase

import android.content.Context
import com.healthconnect.export.data.DailyHealthRecord
import com.healthconnect.export.data.ExportConfig
import com.healthconnect.export.data.ExportSummary
import com.healthconnect.export.data.HealthDataType
import com.healthconnect.export.repository.HealthConnectRepository
import com.healthconnect.export.repository.LocalExportRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Represents a step during the export process.
 * The ViewModel collects this Flow and maps each step to UI state updates.
 */
sealed class ExportStep {
    /** Checking Health Connect availability */
    data object CheckingPermissions : ExportStep()

    /** Health Connect is installed but unavailable */
    data object HealthNotAvailable : ExportStep()

    /** Health Connect is not installed */
    data object HealthNotInstalled : ExportStep()

    /** Permissions are missing — the attached set must be requested */
    data class PermissionsRequired(val permissions: Set<String>) : ExportStep()

    /** Progress message update (e.g. "Reading data…", "Saving 5 days…") */
    data class Progress(val message: String) : ExportStep()

    /** Export completed successfully */
    data class Complete(
        val records: List<DailyHealthRecord>,
        val files: List<File>,
        val summary: ExportSummary
    ) : ExportStep()

    /** Export failed with an error */
    data class Error(val message: String) : ExportStep()
}

/**
 * UseCase that encapsulates the complete export workflow:
 * 1. Check Health Connect availability
 * 2. Check / request permissions
 * 3. Read health data for the period
 * 4. Save as JSON files
 * 5. Compute dashboard summary
 *
 * Emits [ExportStep] events via Flow so the ViewModel can react to each step.
 */
class ExportDataUseCase(
    private val healthRepo: HealthConnectRepository,
    private val localRepo: LocalExportRepository
) {
    /**
     * Execute the export workflow, emitting progress events.
     */
    fun execute(
        context: Context,
        config: ExportConfig,
        startDate: LocalDate,
        endDate: LocalDate
    ): Flow<ExportStep> = flow {
        try {
            // 1. Health Connect availability
            emit(ExportStep.CheckingPermissions)

            val healthAvailable = healthRepo.isHealthConnectAvailable()
            if (!healthAvailable) {
                if (healthRepo.isHealthConnectInstalled()) {
                    emit(ExportStep.HealthNotAvailable)
                } else {
                    emit(ExportStep.HealthNotInstalled)
                }
                return@flow
            }

            // 2. Permissions
            val hasPermissions = healthRepo.checkPermissions(config.enabledTypes)
            if (!hasPermissions) {
                val permissions = healthRepo.getPermissionsForTypes(config.enabledTypes)
                emit(ExportStep.PermissionsRequired(permissions))
                return@flow
            }

            // 3. Read data day-by-day with progress
            val totalDays = ChronoUnit.DAYS.between(startDate, endDate) + 1
            val records = mutableListOf<DailyHealthRecord>()
            var currentDate = startDate
            var dayIndex = 0
            while (!currentDate.isAfter(endDate)) {
                dayIndex++
                emit(ExportStep.Progress("read:$dayIndex:$totalDays:${currentDate}"))
                val record = healthRepo.readDay(
                    currentDate, config.enabledTypes,
                    selectedSourcePackage = config.selectedSourcePackage
                )
                records.add(record)
                currentDate = currentDate.plusDays(1)
            }

            // 4. Save files with progress
            val files = mutableListOf<File>()
            records.forEachIndexed { i, record ->
                emit(ExportStep.Progress("save:${i + 1}:${records.size}:${record.date}"))
                files.add(localRepo.saveDailyRecord(record, config))
            }

            // 5. Compute summary
            val summary = ExportSummary(
                totalSteps = records.sumOf { it.steps?.totalSteps ?: 0L },
                avgHeartRate = records.mapNotNull { it.heartRate?.avgBpm }.let { list ->
                    if (list.isEmpty()) 0.0 else list.average()
                },
                totalCalories = records.sumOf { it.calories?.totalCalories ?: 0.0 },
                totalDistanceMeters = records.sumOf { it.distance?.totalDistanceMeters ?: 0.0 },
                avgSleepMinutes = records.mapNotNull { it.sleep?.totalDurationMinutes }.let { list ->
                    if (list.isEmpty()) 0L else list.average().toLong()
                },
                totalActiveCalories = records.sumOf { it.activeCalories?.totalCalories ?: 0.0 },
                daysCount = records.size,
                startDate = startDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                endDate = endDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
            )

            emit(
                ExportStep.Complete(
                    records = records,
                    files = files,
                    summary = summary
                )
            )
        } catch (e: Exception) {
            emit(ExportStep.Error(e.message ?: "Unknown error"))
        }
    }
}
