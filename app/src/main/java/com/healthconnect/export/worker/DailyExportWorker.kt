package com.healthconnect.export.worker

import android.content.Context
import androidx.work.*
import com.healthconnect.export.data.ExportConfig
import com.healthconnect.export.data.HealthDataType
import com.healthconnect.export.repository.HealthConnectRepository
import com.healthconnect.export.repository.LocalExportRepository
import com.healthconnect.export.repository.GoogleDriveRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Background worker that exports yesterday's health data and optionally syncs to Drive
 */
class DailyExportWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "daily_health_export"
        const val KEY_CONFIG = "export_config"

        fun schedule(context: Context, config: ExportConfig) {
            if (config.frequency == com.healthconnect.export.data.ExportFrequency.MANUAL) {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                return
            }

            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val inputData = workDataOf(
                KEY_CONFIG to config.toString() // Serialize config
            )

            val request = PeriodicWorkRequestBuilder<DailyExportWorker>(
                config.frequency.hours, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setInputData(inputData)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        fun getStatus(context: Context) =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkLiveData(WORK_NAME)
    }

    private val healthRepo = HealthConnectRepository(applicationContext)
    private val localRepo = LocalExportRepository(applicationContext)
    private val driveRepo = GoogleDriveRepository(applicationContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // Default config: all types, export to local, try Drive sync
            val config = ExportConfig(
                enabledTypes = HealthDataType.values().toSet(),
                frequency = com.healthconnect.export.data.ExportFrequency.DAILY,
                autoSyncDrive = true
            )

            // Export yesterday by default (complete day)
            val yesterday = LocalDate.now().minusDays(1)

            // Skip if already exported locally
            if (localRepo.isExported(yesterday, config)) {
                // Still try Drive sync if enabled and connected
                if (config.autoSyncDrive && driveRepo.isSignedIn()) {
                    syncToDrive(yesterday, config)
                }
                return@withContext Result.success()
            }

            // Export from Health Connect
            val records = healthRepo.readPeriod(yesterday, yesterday, config.enabledTypes)

            if (records.isEmpty()) {
                return@withContext Result.success() // No data available yet
            }

            // Save locally
            val files = localRepo.saveRecords(records, config)

            // Sync to Drive if enabled and signed in
            if (config.autoSyncDrive && driveRepo.isSignedIn()) {
                files.forEach { file ->
                    driveRepo.uploadFile(file, "HealthConnectExport/${file.name}")
                }
            }

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private suspend fun syncToDrive(date: LocalDate, config: ExportConfig) {
        val files = localRepo.listExportedFiles(config)
            .filter { it.first == date }
            .map { it.second }

        files.forEach { file ->
            driveRepo.uploadFile(file, "HealthConnectExport/${file.name}")
        }
    }
}
