package com.healthconnect.export.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.healthconnect.export.worker.DeliveryWatchdog

/**
 * Receives the inexact watchdog alarm (see [DeliveryWatchdog]) and kicks the
 * delivery pipeline: re-enqueue the periodic jobs, re-arm the alarm and run a
 * one-shot catch-up for the days missed while the app was idle.
 */
class WatchdogAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        DeliveryWatchdog.onAlarm(context)
    }
}
