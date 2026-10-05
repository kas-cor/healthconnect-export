package com.healthconnect.export.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import com.healthconnect.export.R

/**
 * Foreground-service notification used while a scheduled export runs.
 *
 * Health Connect on Android 14 refuses to read data from the background unless
 * the app either holds `READ_HEALTH_DATA_IN_BACKGROUND` or is running as a
 * foreground service. That permission is dangerous and must be granted by the
 * user, so every background worker promotes itself to a foreground service for
 * the duration of the read instead. Without this the periodic jobs failed with
 * `SecurityException` while a foreground export of the same data succeeded.
 */
object ExportForegroundNotifier {
    const val CHANNEL_ID = "health_export"
    const val NOTIFICATION_ID = 1001

    private const val TAG = "ExportForeground"

    /**
     * Service type used while an export runs.
     *
     * `health` is deliberately NOT used: Android requires BODY_SENSORS /
     * ACTIVITY_RECOGNITION for that type, which only makes sense for apps reading
     * body sensors directly. This app reads Health Connect (a permission-gated
     * provider), and the work it does is synchronising data to storage/Drive/webhook,
     * so `dataSync` is both the accurate type and the one that needs no extra
     * runtime permission.
     */
    private const val SERVICE_TYPE = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

    private const val CHANNEL_NAME = "notification_channel_export"
    private const val CHANNEL_DESC = "notification_channel_export_desc"
    private const val TITLE = "notification_export_title"
    private const val TEXT = "notification_export_text"

    /**
     * Builds the [ForegroundInfo] for a running export.
     *
     * The service type is `health` because the worker reads Health Connect data;
     * it must match `foregroundServiceType` in the manifest, otherwise Android 14
     * refuses the promotion.
     */
    fun foregroundInfo(
        context: Context,
        foregroundServiceType: Int,
    ): ForegroundInfo {
        ensureChannel(context)
        return ForegroundInfo(
            NOTIFICATION_ID,
            buildNotification(context),
            foregroundServiceType,
        )
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_export),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notification_channel_export_desc)
                setShowBadge(false)
            }
        manager.createNotificationChannel(channel)
    }

    /**
     * Promotes the running worker to a foreground service so Health Connect allows
     * the read on Android 14+.
     *
     * Failing to promote is logged but not fatal: the export may still succeed when
     * the app happens to be in the foreground, and losing the whole export over a
     * notification problem would be worse than attempting the read anyway. The
     * Health Connect rejection, if it comes, is reported through [DeliveryLog] as
     * before.
     */
    suspend fun promoteToForeground(worker: CoroutineWorker) {
        try {
            worker.setForeground(
                foregroundInfo(worker.applicationContext, SERVICE_TYPE),
            )
        } catch (e: Exception) {
            Log.w(TAG, "could not enter foreground: ${e.message}", e)
        }
    }

    private fun buildNotification(context: Context): Notification =
        NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notification_export_title))
            .setContentText(context.getString(R.string.notification_export_text))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
}
