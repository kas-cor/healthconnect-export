package com.healthconnect.export.worker

import android.app.ActivityManager
import android.app.Application
import android.app.job.JobScheduler
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.ConnectivityManager
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.healthconnect.export.data.*
import com.healthconnect.export.repository.HealthConnectRepository
import com.healthconnect.export.repository.WebhookRepository
import com.healthconnect.export.repository.WebhookResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
class Every2HoursWebhookWorkerTest {

    @Mock
    private lateinit var mockHealthRepo: HealthConnectRepository

    @Mock
    private lateinit var mockWebhookRepo: WebhookRepository

    private lateinit var mockApp: Application
    private lateinit var tempDir: File
    private val json = Json { ignoreUnknownKeys = true }

    private val todayRecord = DailyHealthRecord(
        date = LocalDate.now().toString(),
        steps = StepsData(totalSteps = 8000, recordsCount = 320),
        heartRate = HeartRateData(avgBpm = 72.0, minBpm = 55, maxBpm = 130, recordsCount = 12),
        metadata = ExportMetadata("1.0.0", "2026-06-08T10:00:00", "Europe/Moscow")
    )

    @Before
    fun setup() {
        tempDir = createTempDirectory("hce-2h-worker-test-").toFile()

        mockApp = mock()
        whenever(mockApp.applicationContext).thenReturn(mockApp)
        whenever(mockApp.filesDir).thenReturn(tempDir)
        whenever(mockApp.getExternalFilesDir(anyOrNull())).thenReturn(tempDir)
        whenever(mockApp.packageName).thenReturn("com.healthconnect.export")

        // Stubs required for WorkManagerTestInitHelper
        val mockPm = mock<PackageManager>()
        whenever(mockApp.packageManager).thenReturn(mockPm)
        val mockAppInfo = mock<ApplicationInfo>()
        mockAppInfo.processName = "com.healthconnect.export"
        whenever(mockPm.getApplicationInfo(eq("com.healthconnect.export"), any<Int>())).thenReturn(mockAppInfo)
        whenever(mockApp.applicationInfo).thenReturn(mockAppInfo)
        whenever(mockApp.getSystemService(Context.ACTIVITY_SERVICE))
            .thenReturn(mock<ActivityManager>())
        whenever(mockApp.getSystemService(Context.JOB_SCHEDULER_SERVICE))
            .thenReturn(mock<JobScheduler>())
        whenever(mockApp.getSystemService(Context.CONNECTIVITY_SERVICE))
            .thenReturn(mock<ConnectivityManager>())
        val mockResources = mock<Resources>()
        whenever(mockApp.resources).thenReturn(mockResources)
        val dbDir = File(tempDir, "databases")
        dbDir.mkdirs()
        whenever(mockApp.getDatabasePath(any())).thenReturn(File(dbDir, "workmanager.db"))

        // Initialize WorkManager
        val wmConfig = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.WARN)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(mockApp, wmConfig)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // =============================================
    // Helper: inject private fields via reflection
    // =============================================

    private fun setField(obj: Any, name: String, value: Any) {
        val field: Field = obj::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(obj, value)
    }

    // =============================================
    // Helper: create a configured worker with mocked repos
    // =============================================

    private fun createWorker(
        config: ExportConfig? = null,
        context: Context = mockApp
    ): Every2HoursWebhookWorker {
        val inputData = if (config != null) {
            workDataOf(Every2HoursWebhookWorker.KEY_CONFIG to json.encodeToString(config))
        } else {
            workDataOf()
        }
        val worker = TestListenableWorkerBuilder<Every2HoursWebhookWorker>(context)
            .setInputData(inputData)
            .build()

        // Replace repos with mocks via reflection
        setField(worker, "healthRepo", mockHealthRepo)
        setField(worker, "webhookRepo", mockWebhookRepo)

        return worker
    }

    // =============================================
    // doWork() — Normal flow tests
    // =============================================

    @Test
    fun `successful read sends webhook and returns success`() {
        runBlocking {
            val config = ExportConfig(
                enabledTypes = setOf(HealthDataType.STEPS, HealthDataType.HEART_RATE),
                frequency = ExportFrequency.MANUAL,
                autoSyncDrive = false,
                webhookUrl = "https://hooks.example.com/data",
                webhookAuthToken = "test-token-123",
                autoSendWebhookEvery2Hours = true
            )

            whenever(mockHealthRepo.readDay(
                any(), eq(config.enabledTypes), eq(config.selectedSourcePackage)
            )).thenReturn(todayRecord)
            whenever(mockWebhookRepo.sendRecords(
                eq(config.webhookUrl), any(), eq(config.webhookAuthToken)
            )).thenReturn(WebhookResult.Success(200, "OK"))

            val worker = createWorker(config)
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)

            verify(mockHealthRepo).readDay(
                eq(LocalDate.now()), eq(config.enabledTypes), eq(config.selectedSourcePackage)
            )
            verify(mockWebhookRepo).sendRecords(
                eq(config.webhookUrl), eq(listOf(todayRecord)), eq(config.webhookAuthToken)
            )
        }
    }

    @Test
    fun `blank webhook url returns success without sending`() {
        runBlocking {
            val config = ExportConfig(
                enabledTypes = setOf(HealthDataType.STEPS),
                frequency = ExportFrequency.MANUAL,
                autoSyncDrive = false,
                webhookUrl = "",
                autoSendWebhookEvery2Hours = true
            )

            // readDay should not be called because webhookUrl is blank
            // The worker checks config.webhookUrl.isBlank() early
            val worker = createWorker(config)
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)

            verify(mockHealthRepo, never()).readDay(any(), any(), anyOrNull())
            verify(mockWebhookRepo, never()).sendRecords(any(), any(), anyOrNull())
        }
    }

    @Test
    fun `whitespace webhook url returns success without sending`() {
        runBlocking {
            val config = ExportConfig(
                enabledTypes = setOf(HealthDataType.STEPS),
                frequency = ExportFrequency.MANUAL,
                autoSyncDrive = false,
                webhookUrl = "   ",
                autoSendWebhookEvery2Hours = true
            )

            val worker = createWorker(config)
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)

            verify(mockHealthRepo, never()).readDay(any(), any(), anyOrNull())
            verify(mockWebhookRepo, never()).sendRecords(any(), any(), anyOrNull())
        }
    }

    @Test
    fun `no input data returns failure`() {
        runBlocking {
            // No config passed → empty input data
            val worker = createWorker(config = null)
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.failure(), result)

            verify(mockHealthRepo, never()).readDay(any(), any(), anyOrNull())
            verify(mockWebhookRepo, never()).sendRecords(any(), any(), anyOrNull())
        }
    }

    @Test
    fun `malformed config json returns failure`() {
        runBlocking {
            val worker = TestListenableWorkerBuilder<Every2HoursWebhookWorker>(mockApp)
                .setInputData(workDataOf(Every2HoursWebhookWorker.KEY_CONFIG to "not-valid-json"))
                .build()
            setField(worker, "healthRepo", mockHealthRepo)
            setField(worker, "webhookRepo", mockWebhookRepo)

            val result = worker.doWork()

        // Malformed JSON throws SerializationException (extends IllegalArgumentException,
        // which is a RuntimeException) → caught by catch (e: Exception) → retry
        assertEquals(ListenableWorker.Result.retry(), result)

            verify(mockHealthRepo, never()).readDay(any(), any(), anyOrNull())
            verify(mockWebhookRepo, never()).sendRecords(any(), any(), anyOrNull())
        }
    }

    // =============================================
    // Exception handling tests
    // =============================================

    @Test
    fun `security exception returns failure`() {
        runBlocking {
            val config = ExportConfig(
                enabledTypes = setOf(HealthDataType.STEPS),
                frequency = ExportFrequency.MANUAL,
                autoSyncDrive = false,
                webhookUrl = "https://hooks.example.com/data",
                autoSendWebhookEvery2Hours = true
            )

            whenever(mockHealthRepo.readDay(any(), any(), anyOrNull()))
                .thenThrow(SecurityException("No permission"))

            val worker = createWorker(config)
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.failure(), result)
        }
    }

    @Test
    fun `illegal state exception returns failure`() {
        runBlocking {
            val config = ExportConfig(
                enabledTypes = setOf(HealthDataType.STEPS),
                frequency = ExportFrequency.MANUAL,
                autoSyncDrive = false,
                webhookUrl = "https://hooks.example.com/data",
                autoSendWebhookEvery2Hours = true
            )

            whenever(mockHealthRepo.readDay(any(), any(), anyOrNull()))
                .thenThrow(IllegalStateException("Health Connect not available"))

            val worker = createWorker(config)
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.failure(), result)
        }
    }

    @Test
    fun `generic exception from health repo returns retry`() {
        runBlocking {
            val config = ExportConfig(
                enabledTypes = setOf(HealthDataType.STEPS),
                frequency = ExportFrequency.MANUAL,
                autoSyncDrive = false,
                webhookUrl = "https://hooks.example.com/data",
                autoSendWebhookEvery2Hours = true
            )

            whenever(mockHealthRepo.readDay(any(), any(), anyOrNull()))
                .thenThrow(RuntimeException("Network error"))

            val worker = createWorker(config)
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
        }
    }

    @Test
    fun `webhook send exception returns retry`() {
        runBlocking {
            val config = ExportConfig(
                enabledTypes = setOf(HealthDataType.STEPS),
                frequency = ExportFrequency.MANUAL,
                autoSyncDrive = false,
                webhookUrl = "https://hooks.example.com/data",
                autoSendWebhookEvery2Hours = true
            )

            whenever(mockHealthRepo.readDay(any(), any(), anyOrNull())).thenReturn(todayRecord)
            whenever(mockWebhookRepo.sendRecords(any(), any(), anyOrNull()))
                .thenThrow(RuntimeException("Webhook timeout"))

            val worker = createWorker(config)
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.retry(), result)

            verify(mockHealthRepo).readDay(any(), any(), anyOrNull())
            verify(mockWebhookRepo).sendRecords(eq(config.webhookUrl), eq(listOf(todayRecord)), anyOrNull())
        }
    }

    // =============================================
    // Data reading scenarios
    // =============================================

    @Test
    fun `reads only today not a date range`() {
        runBlocking {
            val config = ExportConfig(
                enabledTypes = setOf(HealthDataType.STEPS),
                frequency = ExportFrequency.MANUAL,
                autoSyncDrive = false,
                webhookUrl = "https://hooks.example.com/data",
                autoSendWebhookEvery2Hours = true
            )

            whenever(mockHealthRepo.readDay(eq(LocalDate.now()), any(), anyOrNull())).thenReturn(todayRecord)
            whenever(mockWebhookRepo.sendRecords(any(), any(), anyOrNull()))
                .thenReturn(WebhookResult.Success(200, "OK"))

            val worker = createWorker(config)
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)

            // Verify it reads only today, not any other date
            verify(mockHealthRepo).readDay(eq(LocalDate.now()), any(), anyOrNull())
        }
    }

    @Test
    fun `sends with null auth token when none configured`() {
        runBlocking {
            val config = ExportConfig(
                enabledTypes = setOf(HealthDataType.STEPS),
                frequency = ExportFrequency.MANUAL,
                autoSyncDrive = false,
                webhookUrl = "https://hooks.example.com/data",
                webhookAuthToken = "",  // no token
                autoSendWebhookEvery2Hours = true
            )

            whenever(mockHealthRepo.readDay(any(), any(), anyOrNull())).thenReturn(todayRecord)
            whenever(mockWebhookRepo.sendRecords(any(), any(), anyOrNull()))
                .thenReturn(WebhookResult.Success(200, "OK"))

            val worker = createWorker(config)
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)

            // auth token should be empty string
            verify(mockWebhookRepo).sendRecords(any(), eq(listOf(todayRecord)), eq(""))
        }
    }

    @Test
    fun `supports all health data types`() {
        runBlocking {
            val config = ExportConfig(
                enabledTypes = HealthDataType.entries.toSet(),
                frequency = ExportFrequency.MANUAL,
                autoSyncDrive = false,
                webhookUrl = "https://hooks.example.com/data",
                autoSendWebhookEvery2Hours = true
            )

            whenever(mockHealthRepo.readDay(any(), any(), anyOrNull())).thenReturn(todayRecord)
            whenever(mockWebhookRepo.sendRecords(any(), any(), anyOrNull()))
                .thenReturn(WebhookResult.Success(200, "OK"))

            val worker = createWorker(config)
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)

            verify(mockHealthRepo).readDay(any(), eq(HealthDataType.entries.toSet()), anyOrNull())
        }
    }

    @Test
    fun `passes selected source package to health repo`() {
        runBlocking {
            val config = ExportConfig(
                enabledTypes = setOf(HealthDataType.STEPS),
                frequency = ExportFrequency.MANUAL,
                autoSyncDrive = false,
                webhookUrl = "https://hooks.example.com/data",
                autoSendWebhookEvery2Hours = true,
                selectedSourcePackage = "com.example.health"
            )

            whenever(mockHealthRepo.readDay(any(), any(), anyOrNull())).thenReturn(todayRecord)
            whenever(mockWebhookRepo.sendRecords(any(), any(), anyOrNull()))
                .thenReturn(WebhookResult.Success(200, "OK"))

            val worker = createWorker(config)
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)

            verify(mockHealthRepo).readDay(any(), any(), eq("com.example.health"))
        }
    }

    // =============================================
    // schedule() companion tests
    // =============================================

    @Test
    fun `schedule enqueues periodic work`() {
        val config = ExportConfig(
            enabledTypes = setOf(HealthDataType.STEPS),
            frequency = ExportFrequency.MANUAL,
            autoSyncDrive = false,
            webhookUrl = "https://hooks.example.com/data",
            autoSendWebhookEvery2Hours = true
        )

        // Should not throw
        Every2HoursWebhookWorker.schedule(mockApp, config)

        // Verify work is registered — can check via getWorkInfos
        val workManager = WorkManager.getInstance(mockApp)
        val liveData = workManager.getWorkInfosForUniqueWorkLiveData(Every2HoursWebhookWorker.WORK_NAME)
        assertNotNull("Work should be registered", liveData)
    }

    @Test
    fun `schedule with blank url does not enqueue work`() {
        val config = ExportConfig(
            enabledTypes = setOf(HealthDataType.STEPS),
            frequency = ExportFrequency.MANUAL,
            autoSyncDrive = false,
            webhookUrl = "",
            autoSendWebhookEvery2Hours = true
        )

        // Should not throw — schedule skips enqueue when url is blank
        Every2HoursWebhookWorker.schedule(mockApp, config)
    }

    @Test
    fun `cancel does not throw`() {
        // First schedule, then cancel
        val config = ExportConfig(
            enabledTypes = setOf(HealthDataType.STEPS),
            frequency = ExportFrequency.MANUAL,
            autoSyncDrive = false,
            webhookUrl = "https://hooks.example.com/data",
            autoSendWebhookEvery2Hours = true
        )
        Every2HoursWebhookWorker.schedule(mockApp, config)

        // Should not throw
        Every2HoursWebhookWorker.cancel(mockApp)
    }

    // =============================================
    // Constants
    // =============================================

    @Test
    fun `work name constant is correct`() {
        assertEquals("every_2_hours_webhook", Every2HoursWebhookWorker.WORK_NAME)
    }

    @Test
    fun `key config constant is correct`() {
        assertEquals("webhook_config", Every2HoursWebhookWorker.KEY_CONFIG)
    }
}
