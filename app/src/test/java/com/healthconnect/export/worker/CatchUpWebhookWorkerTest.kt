package com.healthconnect.export.worker

import android.app.ActivityManager
import android.app.Application
import android.app.job.JobScheduler
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.ConnectivityManager
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.healthconnect.export.data.DailyHealthRecord
import com.healthconnect.export.data.ExportMetadata
import com.healthconnect.export.data.StepsData
import com.healthconnect.export.repository.HealthConnectRepository
import com.healthconnect.export.repository.WebhookRepository
import com.healthconnect.export.repository.WebhookResult
import com.healthconnect.export.testing.FakeSharedPreferences
import com.healthconnect.export.util.DeliveryLog
import com.healthconnect.export.util.ExportSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.*
import java.io.File
import java.lang.reflect.Field
import java.time.LocalDate
import kotlin.io.path.createTempDirectory

@RunWith(MockitoJUnitRunner.Silent::class)
class CatchUpWebhookWorkerTest {
    @Mock
    private lateinit var mockHealthRepo: HealthConnectRepository

    @Mock
    private lateinit var mockWebhookRepo: WebhookRepository

    private lateinit var mockApp: Application
    private lateinit var prefs: FakeSharedPreferences
    private lateinit var tempDir: File

    private val today: LocalDate = LocalDate.now()

    @Before
    fun setup() {
        tempDir = createTempDirectory("hce-catch-up-test-").toFile()
        prefs = FakeSharedPreferences()

        mockApp = mock()
        whenever(mockApp.applicationContext).thenReturn(mockApp)
        whenever(mockApp.filesDir).thenReturn(tempDir)
        whenever(mockApp.getExternalFilesDir(anyOrNull())).thenReturn(tempDir)
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

    private fun setField(
        obj: Any,
        name: String,
        value: Any,
    ) {
        val field: Field = obj.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(obj, value)
    }

    private fun configureWebhook() {
        prefs
            .edit()
            .putString(ExportSettings.KEY_WEBHOOK_URL, "https://example.com/hook")
            .putBoolean(ExportSettings.KEY_AUTO_SEND_WEBHOOK, true)
            .apply()
    }

    private fun createWorker(trigger: String? = null): CatchUpWebhookWorker {
        val builder = TestListenableWorkerBuilder<CatchUpWebhookWorker>(mockApp)
        if (trigger != null) {
            builder.setInputData(workDataOf(CatchUpWebhookWorker.KEY_TRIGGER to trigger))
        }
        val worker = builder.build()
        setField(worker, "healthRepo", mockHealthRepo)
        setField(worker, "webhookRepo", mockWebhookRepo)
        return worker
    }

    private fun record(date: LocalDate) = DailyHealthRecord(
        date = date.toString(),
        steps = StepsData(totalSteps = 1000, recordsCount = 10),
        metadata = ExportMetadata("1.8", "${date}T20:00:00", "Europe/Moscow"),
    )

    private fun seedResumePoint(date: LocalDate) {
        DeliveryLog.recordSuccess(mockApp, "seed", listOf(date.toString()))
    }

    @Test
    fun `returns success without sending when no webhook is configured`() {
        runBlocking {
            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(mockWebhookRepo, never()).sendRecords(any(), any(), anyOrNull())
            verify(mockHealthRepo, never()).readPeriodInBatch(any(), any(), any(), anyOrNull(), anyOrNull())
        }
    }

    @Test
    fun `sends the days after the last delivered one`() {
        runBlocking {
            configureWebhook()
            seedResumePoint(today.minusDays(4))
            val records = listOf(record(today.minusDays(3)), record(today.minusDays(2)), record(today))
            whenever(mockHealthRepo.readPeriodInBatch(any(), any(), any(), anyOrNull(), anyOrNull())).thenReturn(records)
            whenever(mockWebhookRepo.sendRecords(any(), any(), anyOrNull()))
                .thenReturn(WebhookResult.Success(200, "ok"))

            val result = createWorker("watchdog").doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(mockWebhookRepo).sendRecords(eq("https://example.com/hook"), eq(records), anyOrNull())
            // The resume point moves to the newest delivered day.
            assertEquals(today, DeliveryLog.lastDataDate(mockApp))
            assertNotNull(DeliveryLog.status(mockApp).lastSuccessAt)
            assertTrue(DeliveryLog.entries(mockApp).first().contains("[watchdog]"))
        }
    }

    @Test
    fun `reports idle and sends nothing when everything is already delivered`() {
        runBlocking {
            configureWebhook()
            seedResumePoint(today)

            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(mockHealthRepo, never()).readPeriodInBatch(any(), any(), any(), anyOrNull(), anyOrNull())
            verify(mockWebhookRepo, never()).sendRecords(any(), any(), anyOrNull())
            assertTrue(DeliveryLog.status(mockApp).lastAttemptResult!!.startsWith("idle"))
        }
    }

    @Test
    fun `uses a seven day window on the first run`() {
        runBlocking {
            configureWebhook()
            whenever(mockHealthRepo.readPeriodInBatch(any(), any(), any(), anyOrNull(), anyOrNull()))
                .thenReturn(listOf(record(today)))
            whenever(mockWebhookRepo.sendRecords(any(), any(), anyOrNull()))
                .thenReturn(WebhookResult.Success(200, ""))

            createWorker().doWork()

            verify(mockHealthRepo).readPeriodInBatch(
                eq(today.minusDays(6)),
                eq(today),
                any(),
                anyOrNull(),
                anyOrNull(),
            )
        }
    }

    @Test
    fun `caps the catch-up window at thirty days`() {
        runBlocking {
            configureWebhook()
            seedResumePoint(today.minusDays(90))
            whenever(mockHealthRepo.readPeriodInBatch(any(), any(), any(), anyOrNull(), anyOrNull()))
                .thenReturn(listOf(record(today)))
            whenever(mockWebhookRepo.sendRecords(any(), any(), anyOrNull()))
                .thenReturn(WebhookResult.Success(200, ""))

            createWorker().doWork()

            verify(mockHealthRepo).readPeriodInBatch(
                eq(today.minusDays(CatchUpWebhookWorker.MAX_CATCH_UP_DAYS - 1)),
                eq(today),
                any(),
                anyOrNull(),
                anyOrNull(),
            )
        }
    }

    @Test
    fun `keeps the resume point when there is no health data`() {
        runBlocking {
            configureWebhook()
            val resumePoint = today.minusDays(3)
            seedResumePoint(resumePoint)
            whenever(mockHealthRepo.readPeriodInBatch(any(), any(), any(), anyOrNull(), anyOrNull()))
                .thenReturn(emptyList())

            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(mockWebhookRepo, never()).sendRecords(any(), any(), anyOrNull())
            assertEquals(resumePoint, DeliveryLog.lastDataDate(mockApp))
        }
    }

    @Test
    fun `retries when the webhook reports an error`() {
        runBlocking {
            configureWebhook()
            whenever(mockHealthRepo.readPeriodInBatch(any(), any(), any(), anyOrNull(), anyOrNull()))
                .thenReturn(listOf(record(today)))
            whenever(mockWebhookRepo.sendRecords(any(), any(), anyOrNull()))
                .thenReturn(WebhookResult.Error(503, "unavailable"))

            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
            assertTrue(DeliveryLog.status(mockApp).lastAttemptResult!!.startsWith("failed"))
        }
    }

    @Test
    fun `retries when health permissions are missing`() {
        runBlocking {
            configureWebhook()
            whenever(mockHealthRepo.readPeriodInBatch(any(), any(), any(), anyOrNull(), anyOrNull()))
                .thenThrow(SecurityException("denied"))

            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
            assertTrue(DeliveryLog.status(mockApp).lastAttemptResult!!.contains("permissions"))
        }
    }

    @Test
    fun `retries when health connect is unavailable`() {
        runBlocking {
            configureWebhook()
            whenever(mockHealthRepo.readPeriodInBatch(any(), any(), any(), anyOrNull(), anyOrNull()))
                .thenThrow(IllegalStateException("not installed"))

            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
            assertTrue(DeliveryLog.status(mockApp).lastAttemptResult!!.contains("health connect"))
        }
    }

    @Test
    fun `retries on an unexpected error`() {
        runBlocking {
            configureWebhook()
            whenever(mockHealthRepo.readPeriodInBatch(any(), any(), any(), anyOrNull(), anyOrNull()))
                .thenThrow(RuntimeException("boom"))

            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
            assertTrue(DeliveryLog.status(mockApp).lastAttemptResult!!.contains("boom"))
        }
    }

    @Test
    fun `the catch-up work is registered under a unique name`() {
        assertEquals("webhook_catch_up", DeliveryWatchdog.CATCH_UP_WORK_NAME)
        assertNotNull(WorkManager.getInstance(mockApp))
    }
}
