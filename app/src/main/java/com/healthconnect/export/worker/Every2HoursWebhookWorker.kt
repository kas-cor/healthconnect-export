package com.healthconnect.export.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.healthconnect.export.data.ExportConfig
import com.healthconnect.export.data.LastSend
import com.healthconnect.export.data.SendStats
import com.healthconnect.export.repository.HealthConnectRepository
import com.healthconnect.export.repository.WebhookRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Background worker that reads today's health data every 2 hours
 * and sends it to the configured webhook (without local save or Drive sync).
 *
 * The same worker also performs one-off catch-up runs (see [scheduleCatchUp]):
 * when the periodic work has not run for a while — typical on devices with
 * aggressive battery management — the payload covers every day since the last
 * successful send instead of just today, so missed days are not lost.
 */
class Every2HoursWebhookWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    companion object {
        const val WORK_NAME = "every_2_hours_webhook"
        const val KEY_CONFIG = "webhook_config"

        /** Input key holding the first date of a catch-up run (ISO-8601). */
        const val KEY_FROM_DATE = "from_date"

        /** Unique work name for one-off catch-up runs. */
        const val WORK_NAME_CATCH_UP = "webhook_catch_up"

        /** A gap of at least this many hours since the last success triggers a catch-up. */
        const val STALE_AFTER_HOURS = 6L

        /** Upper bound on the catch-up window; older days are not re-read. */
        const val MAX_CATCH_UP_DAYS = 7L

        private const val TAG = "Every2HoursWebhook"

        private val json = Json { ignoreUnknownKeys = true }

        fun schedule(
            context: Context,
            config: ExportConfig,
        ) {
            if (config.webhookUrl.isBlank()) {
                cancel(context)
                return
            }

            val request =
                PeriodicWorkRequestBuilder<Every2HoursWebhookWorker>(
                    2,
                    TimeUnit.HOURS,
                )
                    // No battery constraint: the 2-hour send is the app's data-delivery
                    // guarantee, and a low-battery deferral here is what let MIUI-style
                    // devices go silent for days.
                    .setInputData(workDataOf(KEY_CONFIG to json.encodeToString(config)))
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

        /**
         * Enqueues (or replaces) a single immediate run that sends every day from
         * [fromDate] up to today. No constraints: the user is waiting for the
         * result, and the periodic work keeps its own battery constraint.
         */
        fun scheduleCatchUp(
            context: Context,
            config: ExportConfig,
            fromDate: LocalDate,
        ) {
            if (config.webhookUrl.isBlank()) return

            val request =
                OneTimeWorkRequestBuilder<Every2HoursWebhookWorker>()
                    .setInputData(
                        workDataOf(
                            KEY_CONFIG to json.encodeToString(config),
                            KEY_FROM_DATE to fromDate.toString(),
                        ),
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                    .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_CATCH_UP,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        /**
         * Self-healing entry point called when the app is opened (and after a
         * reboot/app update): when the last successful send is older than
         * [STALE_AFTER_HOURS], immediately enqueue a catch-up run.
         *
         * Returns true when a catch-up run was enqueued.
         */
        fun scheduleCatchUpIfStale(
            context: Context,
            config: ExportConfig,
            nowMs: Long = System.currentTimeMillis(),
        ): Boolean {
            if (config.webhookUrl.isBlank()) return false
            if (!config.autoSendWebhook && !config.autoSendWebhookEvery2Hours) return false
            val last = SendStats.lastSend(context)
            if (!last.isStale(STALE_AFTER_HOURS, nowMs)) return false

            val fromDate = catchUpFromDate(last)
            Log.i(TAG, "Last successful send is stale (${last.hoursSinceSuccess(nowMs)}h ago), catching up from $fromDate")
            scheduleCatchUp(context, config, fromDate)
            return true
        }

        /**
         * First day missing on the server: the day after the newest record that
         * was successfully delivered, clamped to the last [MAX_CATCH_UP_DAYS] days.
         */
        fun catchUpFromDate(
            last: LastSend,
            today: LocalDate = LocalDate.now(),
        ): LocalDate {
            val candidate = last.lastSentDate?.plusDays(1) ?: today
            val floor = today.minusDays(MAX_CATCH_UP_DAYS)
            return if (candidate.isBefore(floor)) floor else candidate
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

                // A catch-up run carries an explicit start date; the periodic run
                // only covers today.
                val today = LocalDate.now()
                val startDate =
                    inputData.getString(KEY_FROM_DATE)
                        ?.let { raw -> runCatching { LocalDate.parse(raw) }.getOrNull() }
                        ?.let { from -> if (from.isAfter(today)) today else from }
                        ?: today

                val records =
                    healthRepo.readPeriodInBatch(
                        startDate = startDate,
                        endDate = today,
                        types = config.enabledTypes,
                        selectedSourcePackage = config.selectedSourcePackage,
                    )

                if (records.isEmpty()) {
                    Log.w(TAG, "doWork: no health data for $startDate..$today, nothing to send")
                    return@withContext Result.success()
                }

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
