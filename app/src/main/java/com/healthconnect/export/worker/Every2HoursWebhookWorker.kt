package com.healthconnect.export.worker

import android.content.Context
import androidx.work.*
import com.healthconnect.export.data.ExportConfig
import com.healthconnect.export.repository.HealthConnectRepository
import com.healthconnect.export.repository.WebhookRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Background worker that reads today's health data every 2 hours
 * and sends it to the configured webhook (without local save or Drive sync).
 */
class Every2HoursWebhookWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    companion object {
        const val WORK_NAME = "every_2_hours_webhook"
        const val KEY_CONFIG = "webhook_config"
        private val json = Json { ignoreUnknownKeys = true }

        fun schedule(
            context: Context,
            config: ExportConfig,
        ) {
            if (config.webhookUrl.isBlank()) {
                cancel(context)
                return
            }

            val constraints =
                Constraints
                    .Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()

            val inputData =
                workDataOf(
                    KEY_CONFIG to json.encodeToString(config),
                )

            val request =
                PeriodicWorkRequestBuilder<Every2HoursWebhookWorker>(
                    2,
                    TimeUnit.HOURS,
                ).setConstraints(constraints)
                    .setInputData(inputData)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                    .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    private val healthRepo = HealthConnectRepository(applicationContext)
    private val webhookRepo = WebhookRepository(applicationContext)

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            try {
                val configJson = inputData.getString(KEY_CONFIG)
                val config =
                    if (configJson != null) {
                        Json.decodeFromString<ExportConfig>(configJson)
                    } else {
                        return@withContext Result.failure()
                    }

                if (config.webhookUrl.isBlank()) {
                    return@withContext Result.success()
                }

                // Read today's data
                val today = LocalDate.now()
                val records =
                    healthRepo.readPeriodInBatch(
                        startDate = today,
                        endDate = today,
                        types = config.enabledTypes,
                        selectedSourcePackage = config.selectedSourcePackage,
                    )

                // Send to webhook (no local save, no Drive sync)
                when (webhookRepo.sendRecords(config.webhookUrl, records, config.webhookAuthToken)) {
                    is com.healthconnect.export.repository.WebhookResult.Success -> Result.success()
                    is com.healthconnect.export.repository.WebhookResult.Error -> Result.retry()
                }
            } catch (e: SecurityException) {
                Result.failure()
            } catch (e: IllegalStateException) {
                Result.failure()
            } catch (e: Exception) {
                Result.retry()
            }
        }
}
