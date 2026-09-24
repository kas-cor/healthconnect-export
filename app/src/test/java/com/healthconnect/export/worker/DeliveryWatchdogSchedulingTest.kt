package com.healthconnect.export.worker

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.ConnectivityManager
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.healthconnect.export.data.ExportFrequency
import com.healthconnect.export.testing.FakeSharedPreferences
import com.healthconnect.export.util.ExportSettings
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.mockStatic
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.*
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Coverage of the watchdog scheduling paths on the JVM (no Robolectric): the
 * alarm API itself is stubbed, while the "real" alarm semantics — a pending
 * intent that is actually scheduled and can fire in Doze — are asserted in
 * [DeliveryWatchdogTest].
 */
@RunWith(MockitoJUnitRunner.Silent::class)
class DeliveryWatchdogSchedulingTest {
    private lateinit var mockApp: Application
    private lateinit var prefs: FakeSharedPreferences
    private lateinit var tempDir: File

    @Before
    fun setup() {
        tempDir = createTempDirectory("hce-watchdog-scheduling-").toFile()
        prefs = FakeSharedPreferences()

        mockApp = mock()
        whenever(mockApp.applicationContext).thenReturn(mockApp)
        whenever(mockApp.packageName).thenReturn("com.healthconnect.export")
        whenever(mockApp.getSharedPreferences(any(), any())).thenReturn(prefs)

        val mockPm = mock<PackageManager>()
        whenever(mockApp.packageManager).thenReturn(mockPm)
        val mockAppInfo = mock<ApplicationInfo>()
        mockAppInfo.processName = "com.healthconnect.export"
        whenever(mockPm.getApplicationInfo(eq("com.healthconnect.export"), any<Int>())).thenReturn(mockAppInfo)
        whenever(mockApp.applicationInfo).thenReturn(mockAppInfo)
        whenever(mockApp.getSystemService(Context.ACTIVITY_SERVICE)).thenReturn(mock<ActivityManager>())
        whenever(mockApp.getSystemService(Context.JOB_SCHEDULER_SERVICE)).thenReturn(mock<JobScheduler>())
        whenever(mockApp.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(mock<ConnectivityManager>())
        whenever(mockApp.resources).thenReturn(mock<Resources>())
        val dbDir = File(tempDir, "databases")
        dbDir.mkdirs()
        whenever(mockApp.getDatabasePath(any())).thenReturn(File(dbDir, "workmanager.db"))

        WorkManagerTestInitHelper.initializeTestWorkManager(
            mockApp,
            Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build(),
        )
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun configureWebhook() {
        prefs
            .edit()
            .putString(ExportSettings.KEY_WEBHOOK_URL, "https://example.com/hook")
            .putBoolean(ExportSettings.KEY_AUTO_SEND_WEBHOOK_EVERY_2_HOURS, true)
            .apply()
    }

    private fun withStubbedPendingIntent(
        block: (PendingIntent) -> Unit,
    ) {
        val pendingIntent = mock<PendingIntent>()
        mockStatic(PendingIntent::class.java).use { statics ->
            statics
                .`when`<PendingIntent> { PendingIntent.getBroadcast(any(), anyInt(), any<Intent>(), anyInt()) }
                .thenReturn(pendingIntent)
            block(pendingIntent)
        }
    }

    @Test
    fun `ensureScheduled arms the alarm`() {
        val alarmManager = mock<AlarmManager>()
        whenever(mockApp.getSystemService(Context.ALARM_SERVICE)).thenReturn(alarmManager)
        configureWebhook()

        withStubbedPendingIntent { pendingIntent ->
            DeliveryWatchdog.ensureScheduled(mockApp)

            val triggerAt = argumentCaptor<Long>()
            verify(alarmManager)
                .setAndAllowWhileIdle(eq(AlarmManager.RTC_WAKEUP), triggerAt.capture(), same(pendingIntent))
            assertNotNull(triggerAt.firstValue)
        }
    }

    @Test
    fun `ensureScheduled also works for manual frequency without a webhook`() {
        val alarmManager = mock<AlarmManager>()
        whenever(mockApp.getSystemService(Context.ALARM_SERVICE)).thenReturn(alarmManager)
        ExportSettings.saveFrequency(mockApp, ExportFrequency.MANUAL)

        withStubbedPendingIntent {
            DeliveryWatchdog.ensureScheduled(mockApp)

            verify(alarmManager).setAndAllowWhileIdle(anyInt(), anyLong(), ArgumentMatchers.any(PendingIntent::class.java))
        }
    }

    @Test
    fun `scheduleCatchUp and onAlarm keep the pipeline alive`() {
        val alarmManager = mock<AlarmManager>()
        whenever(mockApp.getSystemService(Context.ALARM_SERVICE)).thenReturn(alarmManager)
        configureWebhook()

        withStubbedPendingIntent {
            DeliveryWatchdog.scheduleCatchUp(mockApp, "watchdog")
            DeliveryWatchdog.onAlarm(mockApp)
            DeliveryWatchdog.scheduleCatchUp(mockApp, "boot")

            verify(alarmManager, atLeast(1)).setAndAllowWhileIdle(anyInt(), anyLong(), ArgumentMatchers.any(PendingIntent::class.java))
        }
    }

    @Test
    fun `the alarm is skipped when the alarm service is missing`() {
        DeliveryWatchdog.armAlarm(mockApp)
        DeliveryWatchdog.cancelAlarm(mockApp)
    }

    @Test
    fun `a failing alarm manager never crashes the app`() {
        val alarmManager = mock<AlarmManager>()
        whenever(mockApp.getSystemService(Context.ALARM_SERVICE)).thenReturn(alarmManager)
        doThrow(SecurityException("arm")).whenever(alarmManager).setAndAllowWhileIdle(anyInt(), anyLong(), ArgumentMatchers.any(PendingIntent::class.java))
        doThrow(SecurityException("cancel")).whenever(alarmManager).cancel(ArgumentMatchers.any(PendingIntent::class.java))

        withStubbedPendingIntent {
            DeliveryWatchdog.armAlarm(mockApp)
            DeliveryWatchdog.cancelAlarm(mockApp)

            verify(alarmManager).setAndAllowWhileIdle(anyInt(), anyLong(), ArgumentMatchers.any(PendingIntent::class.java))
            verify(alarmManager).cancel(ArgumentMatchers.any(PendingIntent::class.java))
        }
    }

    @Test
    fun `cancelAlarm cancels the watchdog alarm`() {
        val alarmManager = mock<AlarmManager>()
        whenever(mockApp.getSystemService(Context.ALARM_SERVICE)).thenReturn(alarmManager)

        withStubbedPendingIntent { pendingIntent ->
            DeliveryWatchdog.cancelAlarm(mockApp)

            verify(alarmManager).cancel(same(pendingIntent))
        }
    }
}
