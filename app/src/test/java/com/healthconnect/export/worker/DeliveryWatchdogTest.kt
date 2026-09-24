package com.healthconnect.export.worker

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.healthconnect.export.util.ExportSettings
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeliveryWatchdogTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        ExportSettings
            .prefs(context)
            .edit()
            .putString(ExportSettings.KEY_WEBHOOK_URL, "https://example.com/hook")
            .putBoolean(ExportSettings.KEY_AUTO_SEND_WEBHOOK_EVERY_2_HOURS, true)
            .apply()
    }

    private fun alarmManager(): AlarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

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
    fun `ensureScheduled enqueues the periodic jobs and arms the alarm`() {
        DeliveryWatchdog.ensureScheduled(context)

        assertScheduled(DailyExportWorker.WORK_NAME)
        assertScheduled(Every2HoursWebhookWorker.WORK_NAME)

        val alarm = shadowOf(alarmManager()).nextScheduledAlarm
        assertNotNull("watchdog alarm should be armed", alarm)
        val armed = requireNotNull(alarm)
        assertEquals(AlarmManager.RTC_WAKEUP, armed.type)
        val hoursAhead = (armed.triggerAtTime - System.currentTimeMillis()) / 3_600_000L
        assertTrue("alarm should fire in ~6h, was ${hoursAhead}h", hoursAhead in 5..6)
    }

    @Test
    fun `armAlarm and cancelAlarm tolerate a missing alarm service`() {
        val contextWithoutAlarmService = mock<Context>()
        whenever(contextWithoutAlarmService.getSystemService(Context.ALARM_SERVICE)).thenReturn(null)

        DeliveryWatchdog.armAlarm(contextWithoutAlarmService)
        DeliveryWatchdog.cancelAlarm(contextWithoutAlarmService)

        assertNull(shadowOf(alarmManager()).nextScheduledAlarm)
    }

    @Test
    fun `armAlarm swallows a failing alarm manager`() {
        val alarm = mock<AlarmManager>()
        val contextWithFailingAlarm = mock<Context>()
        whenever(contextWithFailingAlarm.getSystemService(Context.ALARM_SERVICE)).thenReturn(alarm)
        whenever(contextWithFailingAlarm.packageName).thenReturn("com.healthconnect.export")
        doThrow(SecurityException("nope"))
            .whenever(alarm)
            .setAndAllowWhileIdle(ArgumentMatchers.anyInt(), ArgumentMatchers.anyLong(), ArgumentMatchers.any())

        DeliveryWatchdog.armAlarm(contextWithFailingAlarm)

        verify(alarm).setAndAllowWhileIdle(ArgumentMatchers.anyInt(), ArgumentMatchers.anyLong(), ArgumentMatchers.any())
    }

    @Test
    fun `cancelAlarm removes the pending watchdog alarm`() {
        DeliveryWatchdog.ensureScheduled(context)
        assertNotNull(shadowOf(alarmManager()).nextScheduledAlarm)

        DeliveryWatchdog.cancelAlarm(context)

        assertNull(shadowOf(alarmManager()).nextScheduledAlarm)
    }

    @Test
    fun `scheduleCatchUp enqueues the catch-up work`() {
        DeliveryWatchdog.scheduleCatchUp(context, "boot")

        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(DeliveryWatchdog.CATCH_UP_WORK_NAME).get()
        assertEquals(1, infos.size)
        assertNotNull(infos.first())
    }

    @Test
    fun `onAlarm re-enqueues the periodic jobs and the catch-up work`() {
        DeliveryWatchdog.onAlarm(context)

        assertScheduled(DailyExportWorker.WORK_NAME)
        assertScheduled(DeliveryWatchdog.CATCH_UP_WORK_NAME)
        assertNotNull(shadowOf(alarmManager()).nextScheduledAlarm)
    }
}
