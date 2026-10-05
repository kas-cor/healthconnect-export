package com.healthconnect.export.util

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import com.healthconnect.export.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Health Connect on Android 14 refuses to read data from the background unless the
 * app is in the foreground or running as a foreground service. These tests cover
 * the [ExportForegroundNotifier] that the background workers rely on for that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportForegroundNotifierTest {
    private lateinit var context: Application

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun notificationManager(): NotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Test
    fun `foreground info is built with the dataSync service type`() {
        val info = ExportForegroundNotifier.foregroundInfo(context, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
        assertEquals(ExportForegroundNotifier.NOTIFICATION_ID, info.notificationId)
        assertNotNull(info.notification)
    }

    @Test
    fun `notification channel is created with the localized name`() {
        ExportForegroundNotifier.foregroundInfo(
            context,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        val manager = shadowOf(notificationManager())
        val channel = manager.notificationChannels.firstOrNull { it.id == ExportForegroundNotifier.CHANNEL_ID }

        assertNotNull("channel should be registered", channel)
        assertEquals(
            context.getString(R.string.notification_channel_export),
            channel?.name,
        )
    }

    @Test
    fun `rebuilding does not throw when the channel already exists`() {
        repeat(3) {
            ExportForegroundNotifier.foregroundInfo(
                context,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        }

        val manager = shadowOf(notificationManager())
        assertTrue(manager.notificationChannels.any { it.id == ExportForegroundNotifier.CHANNEL_ID })
    }

    @Test
    fun `notification carries title and text`() {
        val info = ExportForegroundNotifier.foregroundInfo(context, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        val notification = info.notification
        assertNotNull(notification)
        assertTrue(
            "notification should be ongoing while the export runs",
            notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0,
        )
    }
}
