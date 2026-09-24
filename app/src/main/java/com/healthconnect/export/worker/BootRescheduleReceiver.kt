package com.healthconnect.export.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.healthconnect.export.data.ExportConfig
import com.healthconnect.export.data.ExportFrequency
import com.healthconnect.export.data.ScheduledConfigStore

/**
 * Re-registers the periodic export/webhook work after a reboot
 * (`BOOT_COMPLETED`) or an app update (`MY_PACKAGE_REPLACED`).
 *
 * WorkManager restores its own work after a reboot, so this receiver is defence
 * in depth: it covers the cases where the work database was dropped or the
 * request was never enqueued (e.g. the app was force-stopped long enough for the
 * OS to clean up, or the app was updated without being opened again). The config
 * comes from [ScheduledConfigStore], i.e. the last schedule the user enabled.
 *
 * Note for ROMs with aggressive battery management (MIUI/HyperOS, EMUI): this
 * receiver only fires when the app is allowed to autostart. The battery
 * optimization exemption offered on the Schedule screen is the part that
 * actually keeps background sending alive there.
 */
class BootRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> reschedule(context, intent.action)
            else -> Log.w(TAG, "Ignoring unexpected action: ${intent.action}")
        }
    }

    private fun reschedule(
        context: Context,
        action: String?,
    ) {
        val config = ScheduledConfigStore.load(context) ?: return
        if (!shouldRestore(config)) return

        Log.i(TAG, "Re-registering scheduled work after $action")
        DailyExportWorker.schedule(context, config)
        // A long power-off or an update gap means the periodic work missed runs.
        Every2HoursWebhookWorker.scheduleCatchUpIfStale(context, config)
    }

    companion object {
        private const val TAG = "BootReschedule"

        /**
         * A stored Manual frequency means the user turned the schedule off, so
         * nothing may be re-registered. Pure decision, kept testable.
         */
        fun shouldRestore(config: ExportConfig): Boolean = config.frequency != ExportFrequency.MANUAL
    }
}
