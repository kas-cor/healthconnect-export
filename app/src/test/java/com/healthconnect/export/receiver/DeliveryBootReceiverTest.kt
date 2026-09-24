package com.healthconnect.export.receiver

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.healthconnect.export.util.ExportSettings
import com.healthconnect.export.worker.DailyExportWorker
import com.healthconnect.export.worker.DeliveryWatchdog
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeliveryBootReceiverTest {
    private lateinit var context: Context
    private val receiver = DeliveryBootReceiver()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        ExportSettings
            .prefs(context)
            .edit()
            .putString(ExportSettings.KEY_WEBHOOK_URL, "https://example.com/hook")
            .putBoolean(ExportSettings.KEY_AUTO_SEND_WEBHOOK, true)
            .apply()
    }

    private fun workState(uniqueName: String): WorkInfo.State? =
        WorkManager
            .getInstance(context)
            .getWorkInfosForUniqueWork(uniqueName)
            .get()
            .firstOrNull()
            ?.state

    private fun assertScheduled(uniqueName: String) {
        assertNotNull("$uniqueName should be scheduled", workState(uniqueName))
    }

    @Test
    fun `boot completed restores the schedule and queues a catch-up`() {
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertScheduled(DailyExportWorker.WORK_NAME)
        assertScheduled(DeliveryWatchdog.CATCH_UP_WORK_NAME)
    }

    @Test
    fun `package replaced restores the schedule`() {
        receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        assertScheduled(DailyExportWorker.WORK_NAME)
    }

    @Test
    fun `quickboot poweron restores the schedule`() {
        receiver.onReceive(context, Intent("android.intent.action.QUICKBOOT_POWERON"))

        assertScheduled(DailyExportWorker.WORK_NAME)
    }

    @Test
    fun `unrelated broadcasts are ignored`() {
        receiver.onReceive(context, Intent(Intent.ACTION_SCREEN_ON))

        assertNull(workState(DailyExportWorker.WORK_NAME))
        assertNull(workState(DeliveryWatchdog.CATCH_UP_WORK_NAME))
    }
}
