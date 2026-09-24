package com.healthconnect.export.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Thin wrapper around the "ignore battery optimizations" exemption.
 *
 * Doze and app-standby buckets are the main reason a periodic WorkManager job
 * can be deferred for hours or days on a phone that has not been touched. The
 * exemption is granted by the user through a system dialog, so the app only
 * offers it (with the current state visible) instead of demanding it.
 */
object BatteryOptimization {
    /** System dialog asking the user to exempt this app from battery optimizations. */
    @SuppressLint("BatteryLife")
    fun requestIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )

    /** True when the app may run in the background without Doze restrictions. */
    fun isIgnored(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return runCatching { powerManager.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(true)
    }
}
