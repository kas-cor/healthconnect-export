package com.healthconnect.export.receiver

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.healthconnect.export.worker.DeliveryWatchdog
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchdogAlarmReceiverTest {
    private lateinit var context: Context
    private val receiver = WatchdogAlarmReceiver()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
    }

    @Test
    fun `alarm enqueues the catch-up delivery`() {
        receiver.onReceive(context, Intent())

        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(DeliveryWatchdog.CATCH_UP_WORK_NAME).get()
        assertNotNull(infos.first())
    }
}
