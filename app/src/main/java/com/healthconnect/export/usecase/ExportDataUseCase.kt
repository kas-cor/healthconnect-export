package com.healthconnect.export.usecase

import android.content.Context
import com.healthconnect.export.data.DailyHealthRecord
import com.healthconnect.export.data.ExportConfig
import com.healthconnect.export.data.ExportSummary
import com.healthconnect.export.data.HealthDataType
import com.healthconnect.export.repository.HealthConnectRepository
import com.healthconnect.export.repository.LocalExportRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

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
    private val localRepo: LocalExportRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
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

            // 3. Read all data in batch (one API call per type instead of N×M calls)
            // onPageProgress is invoked from IO dispatcher (inside readAllPages),
            // so we collect events into a thread-safe list and emit after the call returns.
            val readProgress = mutableListOf<ExportStep>()
            val records = healthRepo.readPeriodInBatch(
                startDate = startDate,
                endDate = endDate,
                types = config.enabledTypes,
                selectedSourcePackage = config.selectedSourcePackage,
                onPageProgress = { typeName, pageNumber ->
                    synchronized(readProgress) {
                        readProgress.add(ExportStep.Progress("read:$typeName:$pageNumber"))
                    }
                }
            )
            // Emit collected page progress (copy outside synchronized to avoid emit inside critical section)
            val progressSnapshot = synchronized(readProgress) { readProgress.toList() }
            progressSnapshot.forEach { emit(it) }

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
