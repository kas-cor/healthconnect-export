package com.healthconnect.export.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.healthconnect.export.data.ExportConfig
import com.healthconnect.export.data.ExportFrequency
import com.healthconnect.export.data.HealthDataType
import com.healthconnect.export.repository.HealthConnectRepository
import com.healthconnect.export.repository.LocalExportRepository
import com.healthconnect.export.repository.GoogleDriveRepository
import com.healthconnect.export.repository.WebhookRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.time.LocalDate
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
        private const val TAG = "DailyExportWorker"
        private val json = Json { ignoreUnknownKeys = true }

        fun schedule(context: Context, config: ExportConfig) {
            if (config.frequency == com.healthconnect.export.data.ExportFrequency.MANUAL) {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                // Also cancel 2-hour webhook if manual is selected
                Every2HoursWebhookWorker.cancel(context)
                return
            }
            // Schedule 2-hour webhook if enabled
            scheduleEvery2HoursWebhook(context, config)

            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val inputData = workDataOf(
                KEY_CONFIG to json.encodeToString(config)
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
            Every2HoursWebhookWorker.cancel(context)
        }

        /**
         * Schedules or cancels the every-2-hours webhook periodic work.
         */
        fun scheduleEvery2HoursWebhook(context: Context, config: ExportConfig) {
            if (config.autoSendWebhookEvery2Hours && config.webhookUrl.isNotBlank()) {
                Every2HoursWebhookWorker.schedule(context, config)
            } else {
                Every2HoursWebhookWorker.cancel(context)
            }
        }

        fun cancelEvery2HoursWebhook(context: Context) {
            Every2HoursWebhookWorker.cancel(context)
        }

        fun getStatus(context: Context) =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkLiveData(WORK_NAME)
    }

    private val healthRepo = HealthConnectRepository(applicationContext)
    private val localRepo = LocalExportRepository(applicationContext)
    private val driveRepo = GoogleDriveRepository(applicationContext)
    private val webhookRepo = WebhookRepository()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val configJson = inputData.getString(KEY_CONFIG)
            val config = if (configJson != null) {
                Json.decodeFromString<ExportConfig>(configJson)
            } else {
                ExportConfig(
                    enabledTypes = HealthDataType.entries.toSet(),
                    frequency = com.healthconnect.export.data.ExportFrequency.DAILY,
                    autoSyncDrive = true
                )
            }

            // Export period ending today (captures current day)
            val endDate = LocalDate.now()
            val startDate = when (config.frequency) {
                ExportFrequency.DAILY -> endDate.minusDays(1)  // yesterday + today
                ExportFrequency.WEEKLY -> endDate.minusDays(6)  // past 7 days ending today
                ExportFrequency.MANUAL -> endDate  // shouldn't happen via schedule, but just today
            }

            // Read health data for the period (batch mode: 1 API call per type)
            val records = healthRepo.readPeriodInBatch(
                startDate = startDate,
                endDate = endDate,
                types = config.enabledTypes,
                selectedSourcePackage = config.selectedSourcePackage
            )

            if (records.isEmpty()) {
                Log.w(TAG, "doWork: no health data returned for period $startDate..$endDate")
                return@withContext Result.success()
            }

            // Save locally
            val files = localRepo.saveRecords(records, config)

            // Sync to Drive if enabled and signed in
            if (config.autoSyncDrive && driveRepo.isSignedIn()) {
                files.forEach { file ->
                    driveRepo.uploadFile(file, "HealthConnectExport/${file.name}")
                }
            }

            // Send to webhook if enabled
            if (config.autoSendWebhook && config.webhookUrl.isNotBlank()) {
                webhookRepo.sendRecords(config.webhookUrl, records, config.webhookAuthToken)
            }

            Result.success()
        } catch (e: SecurityException) {
            Result.failure()
        } catch (e: IllegalStateException) {
            Result.failure()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
