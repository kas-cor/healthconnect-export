package com.healthconnect.export.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.healthconnect.export.worker.DeliveryWatchdog

/**
 * Re-enqueues the background delivery after a reboot or an app update.
 *
 * `RECEIVE_BOOT_COMPLETED` was declared in the manifest from the start, but no
 * receiver ever listened for it: after a reboot (or an update, which cancels
 * the jobs on some OEM ROMs) nothing but a manual app launch restored the
 * schedule.
 */
class DeliveryBootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_QUICKBOOT_POWERON,
            -> {
                Log.i(TAG, "rescheduling delivery after ${intent.action}")
                DeliveryWatchdog.ensureScheduled(context)
                DeliveryWatchdog.scheduleCatchUp(context, "boot")
            }
        }
    }

    private companion object {
        const val TAG = "DeliveryBootReceiver"

        /** Some OEM ROMs (MIUI/HTC) send this instead of ACTION_BOOT_COMPLETED. */
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
