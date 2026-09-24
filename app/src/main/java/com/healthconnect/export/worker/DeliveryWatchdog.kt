package com.healthconnect.export.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.healthconnect.export.receiver.WatchdogAlarmReceiver
import com.healthconnect.export.util.ExportSettings
import java.util.concurrent.TimeUnit

/**
 * Watchdog for the background delivery pipeline.
 *
 * A periodic WorkManager job alone is not a reliable trigger on Android: Doze,
 * app-standby buckets and OEM background restrictions (MIUI et al.) can defer
 * it indefinitely, and a force-stop or an app data reset drops it entirely.
 * Nothing in the app noticed such a stall, so delivery stopped silently until
 * the app was opened again by hand.
 *
 * The watchdog adds two independent triggers:
 *  - an inexact alarm that is allowed to fire during Doze
 *    ([AlarmManager.setAndAllowWhileIdle]) which re-enqueues the periodic jobs
 *    and a one-shot catch-up run;
 *  - a boot/package-replaced receiver (see `DeliveryBootReceiver`) that
 *    re-enqueues everything from the persisted settings.
 *
 * Both paths only *enqueue* work, so they stay cheap and safe to run often.
 */
object DeliveryWatchdog {
    const val ALARM_REQUEST_CODE = 7301

    /** How often the alarm re-arms itself. */
    const val WATCHDOG_INTERVAL_HOURS = 6L

    const val CATCH_UP_WORK_NAME = "webhook_catch_up"
    const val KEY_TRIGGER = "catch_up_trigger"
    private const val TAG = "DeliveryWatchdog"

    /**
     * Re-enqueues the periodic jobs from the persisted settings and re-arms the
     * alarm. Safe to call repeatedly (unique work + `UPDATE`/`REPLACE`).
     */
    fun ensureScheduled(context: Context) {
        val config = ExportSettings.loadConfig(context)
        DailyExportWorker.schedule(context, config)
        armAlarm(context)
    }

    /** Enqueues the one-shot worker that delivers days missed while the app was idle. */
    fun scheduleCatchUp(
        context: Context,
        trigger: String,
    ) {
        val request =
            OneTimeWorkRequestBuilder<CatchUpWebhookWorker>()
                .setInputData(workDataOf(CatchUpWebhookWorker.KEY_TRIGGER to trigger))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            CATCH_UP_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /** Arms (or re-arms) the watchdog alarm. */
    fun armAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try {
            // Inexact and allowed in Doze: no SCHEDULE_EXACT_ALARM permission needed.
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + TimeUnit.HOURS.toMillis(WATCHDOG_INTERVAL_HOURS),
                pendingIntent(context),
            )
        } catch (e: Exception) {
            Log.w(TAG, "armAlarm failed: ${e.message}")
        }
    }

    fun cancelAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try {
            alarmManager.cancel(pendingIntent(context))
        } catch (e: Exception) {
            Log.w(TAG, "cancelAlarm failed: ${e.message}")
        }
    }

    /** Called by [WatchdogAlarmReceiver] when the watchdog alarm fires. */
    fun onAlarm(context: Context) {
        Log.i(TAG, "watchdog alarm fired — re-enqueueing delivery work")
        ensureScheduled(context)
        scheduleCatchUp(context, "watchdog")
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            Intent(context, WatchdogAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
