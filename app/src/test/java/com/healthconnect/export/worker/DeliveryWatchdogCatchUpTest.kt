package com.healthconnect.export.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.healthconnect.export.util.DeliveryLog
import com.healthconnect.export.util.ExportSettings
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the app-start trigger of [DeliveryWatchdog.catchUpIfStale]: opening the
 * app must fill a delivery gap immediately instead of waiting for the next
 * watchdog alarm (up to six hours later).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeliveryWatchdogCatchUpTest {
    private lateinit var context: Context
    private val hourMs = 3_600_000L

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().build(),
        )
        ExportSettings.prefs(context).edit().clear().commit()
    }

    private fun configureWebhook(url: String = "https://example.com/hook") {
        ExportSettings
            .prefs(context)
            .edit()
            .putString(ExportSettings.KEY_WEBHOOK_URL, url)
            .putBoolean(ExportSettings.KEY_AUTO_SEND_WEBHOOK_EVERY_2_HOURS, true)
            .commit()
    }

    private fun recordSuccess(hoursAgo: Long) {
        DeliveryLog.recordSuccess(
            context = context,
            trigger = "seed",
            deliveredDates = listOf("2026-09-20"),
            now = System.currentTimeMillis() - hoursAgo * hourMs,
            statusCode = 200,
        )
    }

    private fun catchUpWorkState() =
        WorkManager
            .getInstance(context)
            .getWorkInfosForUniqueWork(DeliveryWatchdog.CATCH_UP_WORK_NAME)
            .get()
            .firstOrNull()
            ?.state

    @Test
    fun `without a webhook url nothing is enqueued`() {
        assertFalse(DeliveryWatchdog.catchUpIfStale(context, "app_start"))
        assertNull(catchUpWorkState())
    }

    @Test
    fun `without any previous success nothing is enqueued`() {
        configureWebhook()

        assertFalse(DeliveryWatchdog.catchUpIfStale(context, "app_start"))
        assertNull(catchUpWorkState())
    }

    @Test
    fun `a recent success does not trigger a catch-up`() {
        configureWebhook()
        recordSuccess(hoursAgo = 2)

        assertFalse(DeliveryWatchdog.catchUpIfStale(context, "app_start"))
        assertNull(catchUpWorkState())
    }

    @Test
    fun `a success exactly at the threshold does not trigger a catch-up yet`() {
        configureWebhook()
        recordSuccess(hoursAgo = DeliveryWatchdog.WATCHDOG_INTERVAL_HOURS - 1)

        assertFalse(DeliveryWatchdog.catchUpIfStale(context, "app_start"))
    }

    @Test
    fun `a stale success enqueues a catch-up run when the app starts`() {
        configureWebhook()
        recordSuccess(hoursAgo = 30)

        assertTrue(DeliveryWatchdog.catchUpIfStale(context, "app_start"))
        assertNotNull(catchUpWorkState())
    }
}
