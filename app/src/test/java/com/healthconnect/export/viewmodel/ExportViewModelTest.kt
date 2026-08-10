package com.healthconnect.export.viewmodel

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Resources
import android.app.ActivityManager
import android.app.job.JobScheduler
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.activity.result.ActivityResult
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.Operation
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.*
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Status
import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.Task
import com.healthconnect.export.R
import com.healthconnect.export.data.*
import com.healthconnect.export.repository.*
import com.healthconnect.export.usecase.ExportDataUseCase
import com.healthconnect.export.usecase.ExportStep
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.lang.reflect.Field
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlin.io.path.createTempDirectory

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(MockitoJUnitRunner.Silent::class)
class ExportViewModelTest {

    @Mock
    private lateinit var mockHealthRepo: HealthConnectRepository

    @Mock
    private lateinit var mockLocalRepo: LocalExportRepository

    @Mock
    private lateinit var mockDriveRepo: GoogleDriveRepository

    @Mock
    private lateinit var mockWebhookRepo: WebhookRepository

    @Mock
    private lateinit var mockGoogleSignInClient: GoogleSignInClient

    private lateinit var mockApp: Application
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockPrefsEditor: SharedPreferences.Editor
    private lateinit var tempDir: File
    private lateinit var viewModel: ExportViewModel
    private val testDispatcher: TestDispatcher = StandardTestDispatcher()

    private var mockedGoogleSignIn: MockedStatic<GoogleSignIn>? = null

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        tempDir = createTempDirectory("hce-test-").toFile()

        // Mock SharedPreferences.Editor
        mockPrefsEditor = mock()
        whenever(mockPrefsEditor.putString(any(), anyOrNull<String>())).thenReturn(mockPrefsEditor)
        whenever(mockPrefsEditor.putBoolean(any(), any())).thenReturn(mockPrefsEditor)
        whenever(mockPrefsEditor.putInt(any(), any())).thenReturn(mockPrefsEditor)
        whenever(mockPrefsEditor.putStringSet(any(), anyOrNull())).thenReturn(mockPrefsEditor)
        whenever(mockPrefsEditor.remove(any())).thenReturn(mockPrefsEditor)

        // Mock SharedPreferences
        mockPrefs = mock()
        whenever(mockPrefs.edit()).thenReturn(mockPrefsEditor)
        whenever(mockPrefs.getString(any(), anyOrNull())).thenReturn(null)
        whenever(mockPrefs.getStringSet(any(), anyOrNull())).thenReturn(null)
        whenever(mockPrefs.getBoolean(any(), any())).thenReturn(false)
        whenever(mockPrefs.contains(any())).thenReturn(false)

        // Mock Application — provide stubs for real repos used during ViewModel init
        mockApp = mock()
        whenever(mockApp.applicationContext).thenReturn(mockApp)
        whenever(mockApp.filesDir).thenReturn(tempDir)
        whenever(mockApp.getSharedPreferences(any(), any())).thenReturn(mockPrefs)
        whenever(mockApp.getString(any())).thenReturn("test_string")
        whenever(mockApp.getString(any(), any())).thenReturn("test_string")
        whenever(mockApp.getString(any(), any(), any())).thenReturn("test_string")
        whenever(mockApp.packageName).thenReturn("com.healthconnect.export")
        val mockResources = mock<Resources>()
        whenever(mockApp.resources).thenReturn(mockResources)
        // Required by real repos during init (before we swap with mocks)
        whenever(mockApp.getSystemService(android.content.Context.CONNECTIVITY_SERVICE))
            .thenReturn(mock<android.net.ConnectivityManager>())
        whenever(mockApp.getExternalFilesDir(anyOrNull())).thenReturn(tempDir)
        whenever(mockApp.getExternalFilesDirs(anyOrNull())).thenReturn(arrayOf(tempDir))

        // Initialize WorkManager via WorkManagerTestInitHelper
        // This requires stubbing several system services on the mock Application
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
        val dbDir = File(tempDir, "databases")
        dbDir.mkdirs()
        whenever(mockApp.getDatabasePath(any())).thenReturn(File(dbDir, "workmanager.db"))
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.WARN)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(mockApp, config)

        // Mock GoogleSignIn static methods
        mockedGoogleSignIn = Mockito.mockStatic(GoogleSignIn::class.java)
        mockedGoogleSignIn!!.`when`<GoogleSignInClient> {
            GoogleSignIn.getClient(any<Context>(), any<GoogleSignInOptions>())
        }.thenReturn(mockGoogleSignInClient)
        mockedGoogleSignIn!!.`when`<GoogleSignInAccount?> {
            GoogleSignIn.getLastSignedInAccount(any<Context>())
        }.thenReturn(null)

        // Default: silent sign-in and sign-out task mocks — their listeners
        // never fire, so the init block stays deterministic and keeps
        // DriveStatus.NotConnected.
        silentSignInTask()
        signOutTask()

        // Create ViewModel (init block runs here)
        viewModel = ExportViewModel(mockApp)

        // Use test dispatcher for DriveManager's async operations
        viewModel.driveManager.scope = CoroutineScope(testDispatcher)

        // Replace repositories with mocks via reflection
        setField(viewModel, "healthRepo", mockHealthRepo)
        setField(viewModel, "localRepo", mockLocalRepo)
        setField(viewModel.driveManager, "driveRepo", mockDriveRepo)
        setField(viewModel.webhookManager, "webhookRepo", mockWebhookRepo)
        setField(viewModel.webhookManager, "healthRepo", mockHealthRepo)

        // Stub driveRepo.isSignedIn() to return false by default (prevents async Drive calls)
        whenever(mockDriveRepo.isSignedIn()).thenReturn(false)

        // Replace exportUseCase with one that uses mocked repos and test dispatcher
        val useCase = ExportDataUseCase(mockHealthRepo, mockLocalRepo, testDispatcher)
        setField(viewModel, "exportUseCase", useCase)

        // Use testDispatcher for the ViewModel's export scope
        viewModel.exportScope = CoroutineScope(testDispatcher)

        // Use testDispatcher for DriveManager's async operations
        viewModel.driveManager.scope = CoroutineScope(testDispatcher)

        // Clear any messages set during init
        viewModel.clearMessage()
    }

    @After
    fun tearDown() {
        mockedGoogleSignIn?.close()
        mockedGoogleSignIn = null
        Dispatchers.resetMain()
        tempDir.deleteRecursively()
    }

    private fun setField(obj: Any, name: String, value: Any) {
        val field: Field = obj::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(obj, value)
    }

    // =============================================
    // Helper: mock exportUseCase.execute to return a Complete flow
    // =============================================

    private fun mockExportComplete(record: DailyHealthRecord, file: File) {
        val records = listOf(record)
        val files = listOf(file)
        val summary = ExportSummary(
            totalSteps = 1000,
            daysCount = 1,
            startDate = "2026-05-24",
            endDate = "2026-05-24"
        )
        val flow = flowOf<ExportStep>(ExportStep.Complete(records, files, summary))
        val mockUseCase = mock<ExportDataUseCase>()
        whenever(mockUseCase.execute(any(), any(), any(), any())).thenReturn(flow)
        setField(viewModel, "exportUseCase", mockUseCase)
        // Stub listExportedFiles so refreshLocalFiles() doesn't overwrite with empty
        val date = LocalDate.of(2026, 5, 24)
        whenever(mockLocalRepo.listExportedFiles(any())).thenReturn(listOf(date to file))
    }

    // =============================================
    // exportNow() Tests
    // =============================================

    @Test
    fun `exportNow when health not installed shows not installed message`() {
        runTest {
            whenever(mockHealthRepo.isHealthConnectAvailable()).thenReturn(false)
            whenever(mockHealthRepo.isHealthConnectInstalled()).thenReturn(false)

            viewModel.exportNow()

            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isLoading)
            assertNotNull(state.message)
        }
    }

    @Test
    fun `exportNow when health not available but installed shows not available message`() {
        runTest {
            whenever(mockHealthRepo.isHealthConnectAvailable()).thenReturn(false)
            whenever(mockHealthRepo.isHealthConnectInstalled()).thenReturn(true)

            viewModel.exportNow()

            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isLoading)
            assertNotNull(state.message)
        }
    }

    @Test
    fun `exportNow when no permissions sets pendingPermissions and shows message`() {
        runTest {
            whenever(mockHealthRepo.isHealthConnectAvailable()).thenReturn(true)
            whenever(mockHealthRepo.checkPermissions(any())).thenReturn(false)
            whenever(mockHealthRepo.getPermissionsForTypes(any())).thenReturn(setOf("perm1", "perm2"))

            viewModel.exportNow()

            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isLoading)
            assertNotNull(viewModel.pendingPermissions)
            assertEquals(2, viewModel.pendingPermissions?.size)
            assertNotNull(state.message)
        }
    }

    @Test
    fun `exportNow successful export saves files and syncs to drive`() = runTest(testDispatcher) {
        val record = DailyHealthRecord(
            date = "2026-05-24",
            metadata = ExportMetadata(
                appVersion = "1.0.0",
                exportTimestamp = "2026-05-24T12:00:00",
                timezone = "UTC"
            )
        )
        val file = File(tempDir, "health_2026-05-24.json")

        mockExportComplete(record, file)
        whenever(mockDriveRepo.isSignedIn()).thenReturn(true)
        whenever(mockDriveRepo.syncFiles(any<List<File>>())).thenReturn(listOf("file_id"))

        // Set single-day range
        viewModel.setDateRange(LocalDate.of(2026, 5, 24), LocalDate.of(2026, 5, 24))
        // Re-enable auto-sync for this test
        viewModel.setAutoSyncDrive(true)

        viewModel.exportNow()

        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(1, state.exportedFiles.size)
        assertNotNull(state.message)

        verify(mockDriveRepo).syncFiles(any<List<File>>())
    }

    @Test
    fun `exportNow when exception occurs shows error message`() {
        runTest {
            whenever(mockHealthRepo.isHealthConnectAvailable()).thenReturn(true)
            whenever(mockHealthRepo.checkPermissions(any())).thenReturn(true)
            whenever(mockHealthRepo.readPeriodInBatch(any(), any(), any(), anyOrNull(), anyOrNull()))
                .thenThrow(RuntimeException("Test error"))

            viewModel.exportNow()

            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isLoading)
            assertNotNull(state.message)
        }
    }

    @Test
    fun `exportNow successful export does not sync drive when autoSync disabled`() {
        runTest {
            val record = DailyHealthRecord(
                date = "2026-05-24",
                metadata = ExportMetadata(
                    appVersion = "1.0.0",
                    exportTimestamp = "2026-05-24T12:00:00",
                    timezone = "UTC"
                )
            )
            val file = File(tempDir, "health_2026-05-24.json")

            mockExportComplete(record, file)
            whenever(mockDriveRepo.isSignedIn()).thenReturn(false)

            // Disable auto-sync
            viewModel.setAutoSyncDrive(false)

            // Set single-day range
            viewModel.setDateRange(LocalDate.of(2026, 5, 24), LocalDate.of(2026, 5, 24))

            viewModel.exportNow()

            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isLoading)
            assertEquals(1, state.exportedFiles.size)
            assertNotNull(state.message)

            verify(mockDriveRepo, never()).syncFiles(any())
        }
    }

    @Test
    fun `exportNow when webhook enabled sends data to webhook`() {
        runTest {
            val record = DailyHealthRecord(
                date = "2026-05-24",
                metadata = ExportMetadata(
                    appVersion = "1.0.0",
                    exportTimestamp = "2026-05-24T12:00:00",
                    timezone = "UTC"
                )
            )
            val records = listOf(record)
            val file = File(tempDir, "health_2026-05-24.json")

            mockExportComplete(record, file)
            whenever(mockDriveRepo.isSignedIn()).thenReturn(false)
            // Stub webhookRepo to return success
            whenever(mockWebhookRepo.sendRecords(any(), any(), anyOrNull()))
                .thenReturn(WebhookResult.Success(200, ""))

            // Enable webhook
            viewModel.setWebhookUrl("https://example.com/webhook")
            viewModel.setWebhookAuthToken("test-token")
            viewModel.setAutoSendWebhook(true)

            // Set single-day range
            viewModel.setDateRange(LocalDate.of(2026, 5, 24), LocalDate.of(2026, 5, 24))

            viewModel.exportNow()

            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isLoading)
            assertEquals(1, state.exportedFiles.size)

            verify(mockWebhookRepo).sendRecords(
                eq("https://example.com/webhook"),
                eq(records),
                eq("test-token")
            )
        }
    }

    // =============================================
    // testWebhook() Tests
    // =============================================

    @Test
    fun `testWebhook when url is blank shows enter url message`() {
        runTest {
            // URL is blank by default
            viewModel.testWebhook()

            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isTestingWebhook)
            assertNotNull(state.message)
            // The message comes from mock string, not actual resource
        }
    }

    @Test
    fun `testWebhook when url has error shows enter url message`() {
        runTest {
            // Set invalid URL — this triggers webhookUrlError
            viewModel.setWebhookUrl("not-a-valid-url")

            // Mock webhook validation to return false
            whenever(mockWebhookRepo.isValidWebhookUrl(any())).thenReturn(false)
            // Re-set to trigger validation with mocked isValidWebhookUrl
            viewModel.setWebhookUrl("not-a-valid-url")

            viewModel.testWebhook()

            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isTestingWebhook)
            assertNotNull(state.webhookUrlError)
            assertNotNull(state.message)
        }
    }

    @Test
    fun `testWebhook when health data empty shows no data message`() {
        runTest {
            viewModel.setWebhookUrl("https://example.com/webhook")
            whenever(mockWebhookRepo.isValidWebhookUrl(any())).thenReturn(true)
            // Re-set to clear any error
            viewModel.setWebhookUrl("https://example.com/webhook")

            // Mock readPeriodInBatch to return empty list
            whenever(mockHealthRepo.readPeriodInBatch(any(), any(), any(), anyOrNull(), anyOrNull()))
                .thenReturn(emptyList())

            viewModel.testWebhook()

            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isTestingWebhook)
            assertNotNull(state.message)

            verify(mockHealthRepo).readPeriodInBatch(any(), any(), any(), anyOrNull(), anyOrNull())
            verify(mockWebhookRepo, never()).sendRecords(any(), any(), anyOrNull())
        }
    }

    @Test
    fun `testWebhook success sends webhook and shows success message`() {
        runTest {
            val record = DailyHealthRecord(
                date = "2026-05-27",
                metadata = ExportMetadata(
                    appVersion = "1.0.0",
                    exportTimestamp = "2026-05-27T12:00:00",
                    timezone = "UTC"
                )
            )
            val records = listOf(record)

            viewModel.setWebhookUrl("https://example.com/webhook")
            whenever(mockWebhookRepo.isValidWebhookUrl(any())).thenReturn(true)
            viewModel.setWebhookUrl("https://example.com/webhook")

            whenever(mockHealthRepo.readPeriodInBatch(any(), any(), any(), anyOrNull(), anyOrNull()))
                .thenReturn(records)
            whenever(mockWebhookRepo.sendRecords(any(), any(), anyOrNull()))
                .thenReturn(WebhookResult.Success(200, ""))

            viewModel.testWebhook()

            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isTestingWebhook)
            assertNotNull(state.message)

            verify(mockHealthRepo).readPeriodInBatch(any(), any(), any(), anyOrNull(), anyOrNull())
            verify(mockWebhookRepo).sendRecords(
                eq("https://example.com/webhook"),
                eq(records),
                anyOrNull()
            )
        }
    }

    @Test
    fun `testWebhook when sendRecords error shows error message`() {
        runTest {
            val record = DailyHealthRecord(
                date = "2026-05-27",
                metadata = ExportMetadata(
                    appVersion = "1.0.0",
                    exportTimestamp = "2026-05-27T12:00:00",
                    timezone = "UTC"
                )
            )
            val records = listOf(record)

            viewModel.setWebhookUrl("https://example.com/webhook")
            whenever(mockWebhookRepo.isValidWebhookUrl(any())).thenReturn(true)
            viewModel.setWebhookUrl("https://example.com/webhook")

            whenever(mockHealthRepo.readPeriodInBatch(any(), any(), any(), anyOrNull(), anyOrNull()))
                .thenReturn(records)
            whenever(mockWebhookRepo.sendRecords(any(), any(), anyOrNull()))
                .thenReturn(WebhookResult.Error(500, "Internal Server Error"))

            viewModel.testWebhook()

            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isTestingWebhook)
            assertNotNull(state.message)

            verify(mockHealthRepo).readPeriodInBatch(any(), any(), any(), anyOrNull(), anyOrNull())
            verify(mockWebhookRepo).sendRecords(
                eq("https://example.com/webhook"),
                eq(records),
                anyOrNull()
            )
        }
    }

    @Test
    fun `testWebhook exception during read shows error message`() {
        runTest {
            viewModel.setWebhookUrl("https://example.com/webhook")
            whenever(mockWebhookRepo.isValidWebhookUrl(any())).thenReturn(true)
            viewModel.setWebhookUrl("https://example.com/webhook")

            whenever(mockHealthRepo.readPeriodInBatch(any(), any(), any(), anyOrNull(), anyOrNull()))
                .thenThrow(RuntimeException("Network error"))

            viewModel.testWebhook()

            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isTestingWebhook)
            assertNotNull(state.message)

            verify(mockHealthRepo).readPeriodInBatch(any(), any(), any(), anyOrNull(), anyOrNull())
        }
    }

    @Test
    fun `cancelTestWebhook cancels running test and resets state`() {
        runTest {
            viewModel.setWebhookUrl("https://example.com/webhook")
            whenever(mockWebhookRepo.isValidWebhookUrl(any())).thenReturn(true)
            viewModel.setWebhookUrl("https://example.com/webhook")

            // testWebhook() sets isTestingWebhook = true synchronously before launching the coroutine
            viewModel.testWebhook()

            // isTestingWebhook is set before the coroutine starts (StandardTestDispatcher delays execution)
            assertTrue(viewModel.uiState.value.isTestingWebhook)

            // Cancel the test before the coroutine executes
            viewModel.cancelTestWebhook()

            val state = viewModel.uiState.value
            assertFalse(state.isTestingWebhook)
            assertNotNull(state.message)
        }
    }

    // =============================================
    // setSourcePackage() Tests
    // =============================================

    @Test
    fun `setSourcePackage updates state and saves to preferences`() {
        viewModel.setSourcePackage("com.test.package")

        val state = viewModel.uiState.value
        assertEquals("com.test.package", state.selectedSourcePackage)
        verify(mockPrefsEditor).putString("selected_source_package", "com.test.package")
        verify(mockPrefsEditor).apply()
    }

    @Test
    fun `setSourcePackage with null clears source`() {
        viewModel.setSourcePackage(null)

        val state = viewModel.uiState.value
        assertNull(state.selectedSourcePackage)
        verify(mockPrefsEditor).putString("selected_source_package", null)
        verify(mockPrefsEditor).apply()
    }

    @Test
    fun `setSourcePackage multiple times updates correctly`() {
        viewModel.setSourcePackage("com.first.pkg")
        assertEquals("com.first.pkg", viewModel.uiState.value.selectedSourcePackage)

        viewModel.setSourcePackage("com.second.pkg")
        assertEquals("com.second.pkg", viewModel.uiState.value.selectedSourcePackage)

        viewModel.setSourcePackage(null)
        assertNull(viewModel.uiState.value.selectedSourcePackage)
    }

    // =============================================
    // handleSignInResult() Tests
    // =============================================

    @Test
    fun `handleSignInResult successful sign-in sets drive connected`() {
        val mockAccount = mock<GoogleSignInAccount>()
        whenever(mockAccount.email).thenReturn("test@example.com")

        val mockTask = mock<Task<GoogleSignInAccount>>()
        whenever(mockTask.getResult(ApiException::class.java)).thenReturn(mockAccount)

        mockedGoogleSignIn!!.`when`<Task<GoogleSignInAccount>> {
            GoogleSignIn.getSignedInAccountFromIntent(any())
        }.thenReturn(mockTask)

        // Stub isSignedIn to return true so refreshDriveStatus doesn't override Connected
        whenever(mockDriveRepo.isSignedIn()).thenReturn(true)

        val intent = mock<Intent>()
        val result = ActivityResult(Activity.RESULT_OK, intent)

        viewModel.handleSignInResult(result)

        val state = viewModel.uiState.value
        assertTrue(state.driveStatus is DriveStatus.Connected)
    }

    @Test
    fun `handleSignInResult api exception sets drive error`() {
        val apiException = ApiException(Status(10, "DEVELOPER_ERROR"))
        val mockTask = mock<Task<GoogleSignInAccount>>()
        whenever(mockTask.getResult(ApiException::class.java)).thenThrow(apiException)

        mockedGoogleSignIn!!.`when`<Task<GoogleSignInAccount>> {
            GoogleSignIn.getSignedInAccountFromIntent(any())
        }.thenReturn(mockTask)

        val intent = mock<Intent>()
        val result = ActivityResult(Activity.RESULT_OK, intent)

        viewModel.handleSignInResult(result)

        val state = viewModel.uiState.value
        assertTrue(state.driveStatus is DriveStatus.Error)
        // The error string comes from mock Application.getString() which returns "test_string"
        assertEquals("test_string", (state.driveStatus as DriveStatus.Error).error)
        assertNotNull(state.message)
    }

    @Test
    fun `handleSignInResult with null account does not update status`() {
        val mockTask = mock<Task<GoogleSignInAccount>>()
        whenever(mockTask.getResult(ApiException::class.java)).thenReturn(null)

        mockedGoogleSignIn!!.`when`<Task<GoogleSignInAccount>> {
            GoogleSignIn.getSignedInAccountFromIntent(any())
        }.thenReturn(mockTask)

        val intent = mock<Intent>()
        val result = ActivityResult(Activity.RESULT_OK, intent)

        viewModel.handleSignInResult(result)

        val state = viewModel.uiState.value
        assertTrue(state.driveStatus is DriveStatus.NotConnected)
    }

    // =============================================
    // silentSignIn() / Drive auto-restore Tests
    // =============================================

    @Test
    fun `refreshDriveStatus silently restores session and connects`() {
        runTest {
            whenever(mockDriveRepo.isSignedIn()).thenReturn(false)
            whenever(mockDriveRepo.listDriveFiles()).thenReturn(emptyList())
            val task = silentSignInTask()

            viewModel.refreshDriveStatus()

            // Fire the success listener as Google would after a silent restore
            argumentCaptor<OnSuccessListener<GoogleSignInAccount>>().apply {
                verify(task).addOnSuccessListener(capture())
                firstValue.onSuccess(mock<GoogleSignInAccount>())
            }
            testDispatcher.scheduler.advanceUntilIdle()

            // Called once in ViewModel init (setup) and once here
            verify(mockGoogleSignInClient, atLeastOnce()).silentSignIn()
            val state = viewModel.uiState.value
            assertTrue(state.driveStatus is DriveStatus.Synced)
            // The silent restore must not surface any snackbar/message
            assertNull(state.message)
        }
    }

    @Test
    fun `refreshDriveStatus stays not connected when silent sign-in fails`() {
        runTest {
            whenever(mockDriveRepo.isSignedIn()).thenReturn(false)
            val task = silentSignInTask()

            viewModel.refreshDriveStatus()

            // Fire the failure listener (no cached session)
            argumentCaptor<OnFailureListener>().apply {
                verify(task).addOnFailureListener(capture())
                firstValue.onFailure(Exception("no cached session"))
            }
            testDispatcher.scheduler.advanceUntilIdle()

            // Called once in ViewModel init (setup) and once here
            verify(mockGoogleSignInClient, atLeastOnce()).silentSignIn()
            val state = viewModel.uiState.value
            assertTrue(state.driveStatus is DriveStatus.NotConnected)
            // The silent failure must not surface any snackbar/message
            assertNull(state.message)
        }
    }

    @Test
    fun `silent sign-in failure at launch shows not connected without a message`() {
        runTest {
            whenever(mockDriveRepo.isSignedIn()).thenReturn(false)
            val task = silentSignInTask()

            // Recreate the ViewModel so the init block runs the silent sign-in
            val vm = ExportViewModel(mockApp)
            vm.driveManager.scope = CoroutineScope(testDispatcher)
            setField(vm.driveManager, "driveRepo", mockDriveRepo)
            setField(vm, "healthRepo", mockHealthRepo)
            setField(vm, "localRepo", mockLocalRepo)
            setField(vm.webhookManager, "webhookRepo", mockWebhookRepo)
            setField(vm.webhookManager, "healthRepo", mockHealthRepo)
            setField(vm, "exportUseCase", ExportDataUseCase(mockHealthRepo, mockLocalRepo, testDispatcher))
            vm.exportScope = CoroutineScope(testDispatcher)

            // The silent sign-in started during init fails (no cached session)
            argumentCaptor<OnFailureListener>().apply {
                verify(task).addOnFailureListener(capture())
                firstValue.onFailure(Exception("no cached session"))
            }
            testDispatcher.scheduler.advanceUntilIdle()

            val state = vm.uiState.value
            assertTrue(state.driveStatus is DriveStatus.NotConnected)
            // No confusing snackbar at startup — just the regular card state
            assertNull(state.message)
        }
    }

    @Test
    fun `sign out prevents silent sign-in for the rest of the session`() {
        runTest {
            whenever(mockDriveRepo.isSignedIn()).thenReturn(false)
            val signOutTask = signOutTask()

            viewModel.signOut()
            argumentCaptor<OnCompleteListener<Void>>().apply {
                verify(signOutTask).addOnCompleteListener(capture())
                firstValue.onComplete(mock())
            }
            testDispatcher.scheduler.advanceUntilIdle()

            // A status refresh after sign-out must NOT trigger a silent sign-in
            viewModel.refreshDriveStatus()
            testDispatcher.scheduler.advanceUntilIdle()

            // silentSignIn was called once in setup init — no new call after sign-out
            verify(mockGoogleSignInClient, times(1)).silentSignIn()
            val state = viewModel.uiState.value
            assertTrue(state.driveStatus is DriveStatus.NotConnected)
        }
    }

    @Test
    fun `stale silent sign-in success after sign out does not reconnect`() {
        runTest {
            whenever(mockDriveRepo.isSignedIn()).thenReturn(false)
            whenever(mockDriveRepo.listDriveFiles()).thenReturn(emptyList())
            val task = silentSignInTask()
            val signOutTask = signOutTask()

            // A silent sign-in attempt starts (as at app start)
            viewModel.refreshDriveStatus()

            // The user signs out while the silent attempt is still in flight
            viewModel.signOut()
            argumentCaptor<OnCompleteListener<Void>>().apply {
                verify(signOutTask).addOnCompleteListener(capture())
                firstValue.onComplete(mock())
            }

            // The stale silent sign-in completes successfully — must NOT reconnect
            argumentCaptor<OnSuccessListener<GoogleSignInAccount>>().apply {
                verify(task).addOnSuccessListener(capture())
                firstValue.onSuccess(mock<GoogleSignInAccount>())
            }
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.driveStatus is DriveStatus.NotConnected)
        }
    }

    @Test
    fun `manual sign in after sign out re-enables silent sign-in`() {
        runTest {
            val signOutTask = signOutTask()

            viewModel.signOut()
            argumentCaptor<OnCompleteListener<Void>>().apply {
                verify(signOutTask).addOnCompleteListener(capture())
                firstValue.onComplete(mock())
            }

            // A fresh manual sign-in clears the session signed-out flag
            val mockAccount = mock<GoogleSignInAccount>()
            whenever(mockAccount.email).thenReturn("test@example.com")
            val mockTask = mock<Task<GoogleSignInAccount>>()
            whenever(mockTask.getResult(ApiException::class.java)).thenReturn(mockAccount)
            mockedGoogleSignIn!!.`when`<Task<GoogleSignInAccount>> {
                GoogleSignIn.getSignedInAccountFromIntent(any())
            }.thenReturn(mockTask)
            whenever(mockDriveRepo.isSignedIn()).thenReturn(true)
            whenever(mockDriveRepo.listDriveFiles()).thenReturn(emptyList())
            viewModel.handleSignInResult(ActivityResult(Activity.RESULT_OK, mock<Intent>()))
            testDispatcher.scheduler.advanceUntilIdle()

            // After re-signing in, silent sign-in is allowed again
            whenever(mockDriveRepo.isSignedIn()).thenReturn(false)
            val task = silentSignInTask()
            viewModel.refreshDriveStatus()
            testDispatcher.scheduler.advanceUntilIdle()

            verify(task).addOnSuccessListener(any<OnSuccessListener<GoogleSignInAccount>>())
        }
    }

    @Test
    fun `init silently restores drive session when account is cached`() {
        runTest {
            // Stub a cached session: silent sign-in succeeds without UI
            val task = silentSignInTask()

            // Recreate the ViewModel so the init block runs with the stub in place
            val vm = ExportViewModel(mockApp)
            vm.driveManager.scope = CoroutineScope(testDispatcher)
            setField(vm.driveManager, "driveRepo", mockDriveRepo)
            setField(vm, "healthRepo", mockHealthRepo)
            setField(vm, "localRepo", mockLocalRepo)
            setField(vm.webhookManager, "webhookRepo", mockWebhookRepo)
            setField(vm.webhookManager, "healthRepo", mockHealthRepo)
            setField(vm, "exportUseCase", ExportDataUseCase(mockHealthRepo, mockLocalRepo, testDispatcher))
            vm.exportScope = CoroutineScope(testDispatcher)
            whenever(mockDriveRepo.listDriveFiles()).thenReturn(emptyList())

            // Fire the silent sign-in success listener captured during init
            argumentCaptor<OnSuccessListener<GoogleSignInAccount>>().apply {
                verify(task).addOnSuccessListener(capture())
                firstValue.onSuccess(mock<GoogleSignInAccount>())
            }
            testDispatcher.scheduler.advanceUntilIdle()

            // Called once in the setup ViewModel init and once in the recreated one
            verify(mockGoogleSignInClient, atLeastOnce()).silentSignIn()
            val state = vm.uiState.value
            assertTrue(
                state.driveStatus is DriveStatus.Connected || state.driveStatus is DriveStatus.Synced
            )
        }
    }

    // =============================================
    // isVersionNewer() Tests
    // =============================================

    @Test
    fun `isVersionNewer returns true when latest is newer`() {
        assertTrue(viewModel.isVersionNewer("1.7", "1.6"))
        assertTrue(viewModel.isVersionNewer("2.0", "1.9.9"))
        assertTrue(viewModel.isVersionNewer("1.6.1", "1.6"))
        assertTrue(viewModel.isVersionNewer("10.0", "9.99"))
    }

    @Test
    fun `isVersionNewer returns false when latest is older or equal`() {
        assertFalse(viewModel.isVersionNewer("1.6", "1.7"))
        assertFalse(viewModel.isVersionNewer("1.6", "1.6"))
        assertFalse(viewModel.isVersionNewer("1.6", "1.6.1"))
        assertFalse(viewModel.isVersionNewer("0.9", "1.0"))
    }

    @Test
    fun `isVersionNewer handles non-numeric segments`() {
        assertFalse(viewModel.isVersionNewer("1.beta", "1.0"))
        assertTrue(viewModel.isVersionNewer("1.5", "1.beta"))
        assertFalse(viewModel.isVersionNewer("", "1.0"))
    }

    // =============================================
    // fetchLatestRelease() Tests (local HTTP server)
    // =============================================

    @Test
    fun `fetchLatestRelease returns Available when redirect points to newer version`() {
        withRedirectServer(location = "/kas-cor/healthconnect-export/releases/tag/v9.9.9") { url ->
            val result = viewModel.fetchLatestRelease(url)

            assertTrue(result is UpdateCheckState.Available)
            val available = result as UpdateCheckState.Available
            assertEquals("9.9.9", available.latestVersion)
            assertEquals(
                "https://github.com/kas-cor/healthconnect-export/releases/tag/v9.9.9",
                available.downloadUrl
            )
        }
    }

    @Test
    fun `fetchLatestRelease resolves absolute redirect location`() {
        withRedirectServer(
            location = "https://github.com/kas-cor/healthconnect-export/releases/tag/v9.9.9"
        ) { url ->
            val result = viewModel.fetchLatestRelease(url)

            assertTrue(result is UpdateCheckState.Available)
            val available = result as UpdateCheckState.Available
            assertEquals("9.9.9", available.latestVersion)
            assertEquals(
                "https://github.com/kas-cor/healthconnect-export/releases/tag/v9.9.9",
                available.downloadUrl
            )
        }
    }

    @Test
    fun `fetchLatestRelease returns UpToDate when tag is not newer`() {
        // v0.1.0 is always older than any installed release version
        withRedirectServer(location = "/kas-cor/healthconnect-export/releases/tag/v0.1.0") { url ->
            val result = viewModel.fetchLatestRelease(url)

            assertTrue(result is UpdateCheckState.UpToDate)
        }
    }

    @Test
    fun `fetchLatestRelease returns Error when response is not a redirect`() {
        withRedirectServer(responseCode = 200, location = null) { url ->
            val result = viewModel.fetchLatestRelease(url)

            assertTrue(result is UpdateCheckState.Error)
        }
    }

    @Test
    fun `fetchLatestRelease returns Error when redirect points to releases page without a tag`() {
        // GitHub redirects /releases/latest to /releases when the repo has no releases
        withRedirectServer(location = "/kas-cor/healthconnect-export/releases") { url ->
            val result = viewModel.fetchLatestRelease(url)

            assertTrue(result is UpdateCheckState.Error)
        }
    }

    @Test
    fun `fetchLatestRelease returns Error on invalid url`() {
        val result = viewModel.fetchLatestRelease("not-a-url")

        assertTrue(result is UpdateCheckState.Error)
    }

    @Test
    fun `fetchLatestRelease returns Error on network exception`() {
        val result = viewModel.fetchLatestRelease("http://127.0.0.1:1/releases/latest")

        assertTrue(result is UpdateCheckState.Error)
    }

    // =============================================
    // checkForUpdates() Tests
    // =============================================

    @Test
    fun `checkForUpdates sets state to Checking synchronously and completes to Available`() {
        runTest {
            withRedirectServer(location = "/kas-cor/healthconnect-export/releases/tag/v9.9.9") { url ->
                viewModel.checkForUpdates(url)

                // State flips to Checking before the coroutine runs
                assertTrue(viewModel.uiState.value.updateCheckState is UpdateCheckState.Checking)

                // Drive the coroutine through the real IO hop to completion
                val deadline = System.currentTimeMillis() + 5_000
                while (viewModel.uiState.value.updateCheckState is UpdateCheckState.Checking) {
                    if (System.currentTimeMillis() > deadline) {
                        fail("Timed out waiting for update check to complete")
                    }
                    testDispatcher.scheduler.advanceUntilIdle()
                    Thread.sleep(10)
                }

                val state = viewModel.uiState.value.updateCheckState
                assertTrue(state is UpdateCheckState.Available)
                assertEquals("9.9.9", (state as UpdateCheckState.Available).latestVersion)
            }
        }
    }

    @Test
    fun `checkForUpdates ignores calls while already checking and reset returns to idle`() {
        runTest {
            viewModel.checkForUpdates("http://127.0.0.1:1/releases/latest")
            assertTrue(viewModel.uiState.value.updateCheckState is UpdateCheckState.Checking)

            // Second call while Checking must be ignored (no new coroutine state)
            viewModel.checkForUpdates("http://127.0.0.1:1/releases/latest")
            assertTrue(viewModel.uiState.value.updateCheckState is UpdateCheckState.Checking)

            viewModel.resetUpdateCheck()
            assertTrue(viewModel.uiState.value.updateCheckState is UpdateCheckState.Idle)

            // Let the queued coroutines finish (connection to port 1 fails instantly)
            testDispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(50)
            testDispatcher.scheduler.advanceUntilIdle()
        }
    }

    // =============================================
    // fetchLatestReleaseNotes() Tests (local HTTP server)
    // =============================================

    @Test
    fun `fetchLatestReleaseNotes returns release body on success`() {
        val releaseJson = """{"tag_name":"v9.9.9","name":"v9.9.9","body":"New feature A\nFixed bug B"}"""
        withHttpServer(body = releaseJson) { url ->
            val notes = viewModel.fetchLatestReleaseNotes(url)

            assertNotNull(notes)
            assertTrue(notes!!.contains("New feature A"))
            assertTrue(notes.contains("Fixed bug B"))
        }
    }

    @Test
    fun `fetchLatestReleaseNotes returns null on server error`() {
        withHttpServer(responseCode = 500, body = "") { url ->
            val notes = viewModel.fetchLatestReleaseNotes(url)

            assertNull(notes)
        }
    }

    @Test
    fun `fetchLatestReleaseNotes returns null when body is blank`() {
        withHttpServer(body = """{"tag_name":"v9.9.9","body":""}""") { url ->
            val notes = viewModel.fetchLatestReleaseNotes(url)

            assertNull(notes)
        }
    }

    @Test
    fun `fetchLatestReleaseNotes returns null on invalid url`() {
        val notes = viewModel.fetchLatestReleaseNotes("not-a-url")

        assertNull(notes)
    }

    @Test
    fun `fetchLatestReleaseNotes returns null on network exception`() {
        val notes = viewModel.fetchLatestReleaseNotes("http://127.0.0.1:1/releases/latest")

        assertNull(notes)
    }

    @Test
    fun `checkForUpdates attaches release notes when update available`() {
        runTest {
            val releaseJson = """{"tag_name":"v9.9.9","body":"Brand new UI\nLots of fixes"}"""
            withHttpServer(body = releaseJson) { apiUrl ->
                withRedirectServer(location = "/kas-cor/healthconnect-export/releases/tag/v9.9.9") { url ->
                    viewModel.checkForUpdates(url, apiUrl)

                    val deadline = System.currentTimeMillis() + 5_000
                    while (viewModel.uiState.value.updateCheckState is UpdateCheckState.Checking) {
                        if (System.currentTimeMillis() > deadline) {
                            fail("Timed out waiting for update check to complete")
                        }
                        testDispatcher.scheduler.advanceUntilIdle()
                        Thread.sleep(10)
                    }

                    val state = viewModel.uiState.value.updateCheckState
                    assertTrue(state is UpdateCheckState.Available)
                    val available = state as UpdateCheckState.Available
                    assertEquals("9.9.9", available.latestVersion)
                    assertNotNull(available.releaseNotes)
                    assertTrue(available.releaseNotes!!.contains("Brand new UI"))
                }
            }
        }
    }

    // =============================================
    // Helpers: local HTTP servers
    // =============================================

    /**
     * Starts a local HTTP server on a random port that answers a single request
     * with the given status code, body and headers, then runs [block].
     */
    private fun withHttpServer(
        responseCode: Int = 200,
        body: String = "",
        headers: Map<String, String> = emptyMap(),
        block: (url: String) -> Unit,
    ) {
        val server = ServerSocket(0)
        server.soTimeout = 5_000
        val port = server.localPort
        val url = "http://127.0.0.1:$port/releases/latest"

        val serverThread = Thread {
            try {
                val client = try { server.accept() } catch (_: Exception) { return@Thread }
                client.use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val headerBlock = headers.map { (k, v) -> "$k: $v\r\n" }.joinToString("")
                    val response = "HTTP/1.1 $responseCode \r\n$headerBlock" +
                        "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
                    socket.getOutputStream().write(response.toByteArray())
                    socket.getOutputStream().flush()
                }
            } catch (_: Exception) {
                // Server closed — ignore
            }
        }
        serverThread.start()

        try {
            block(url)
        } finally {
            serverThread.join(2_000)
            server.close()
        }
    }

    /**
     * Starts a local HTTP server that answers with a redirect (Location header).
     */
    private fun withRedirectServer(
        responseCode: Int = 302,
        location: String? = null,
        block: (url: String) -> Unit,
    ) {
        val headers = if (location != null) mapOf("Location" to location) else emptyMap()
        withHttpServer(responseCode = responseCode, body = "", headers = headers, block = block)
    }

    // =============================================
    // Export format / schedule hour / retention / file actions
    // =============================================

    @Test
    fun `setExportFormat updates state and persists`() {
        viewModel.setExportFormat(ExportFormat.CSV)

        assertEquals(ExportFormat.CSV, viewModel.uiState.value.exportFormat)
        verify(mockPrefsEditor).putString("export_format", "CSV")
    }

    @Test
    fun `setExportFormat persists JSON format`() {
        viewModel.setExportFormat(ExportFormat.JSON)

        assertEquals(ExportFormat.JSON, viewModel.uiState.value.exportFormat)
        verify(mockPrefsEditor).putString("export_format", "JSON")
    }

    @Test
    fun `setScheduleHour updates state persists and reschedules`() {
        viewModel.setFrequency(ExportFrequency.DAILY)

        viewModel.setScheduleHour(7)

        assertEquals(7, viewModel.uiState.value.scheduleHour)
        verify(mockPrefsEditor).putInt("schedule_hour", 7)
    }

    @Test
    fun `setScheduleHour with null clears persisted hour`() {
        viewModel.setScheduleHour(null)

        assertNull(viewModel.uiState.value.scheduleHour)
        verify(mockPrefsEditor).remove("schedule_hour")
    }

    @Test
    fun `setRetentionDays updates state and persists`() {
        viewModel.setRetentionDays(30)

        assertEquals(30, viewModel.uiState.value.retentionDays)
        verify(mockPrefsEditor).putInt("retention_days", 30)
    }

    @Test
    fun `setRetentionDays with null disables cleanup`() {
        viewModel.setRetentionDays(null)

        assertNull(viewModel.uiState.value.retentionDays)
        verify(mockPrefsEditor).remove("retention_days")
    }

    @Test
    fun `deleteExportFile deletes file and refreshes list`() {
        val file = File(tempDir, "health_2026-05-24.json")
        file.writeText("{}")
        whenever(mockLocalRepo.listExportedFiles(any())).thenReturn(emptyList())
        whenever(mockApp.getString(any(), any())).thenReturn("Deleted ${file.name}")

        viewModel.deleteExportFile(file)

        verify(mockLocalRepo).deleteExport(eq(LocalDate.of(2026, 5, 24)), any())
        verify(mockLocalRepo).listExportedFiles(any())
        assertEquals("Deleted ${file.name}", viewModel.uiState.value.message)
    }

    @Test
    fun `deleteExportFile with invalid filename does nothing`() {
        val file = File(tempDir, "readme.json")
        file.writeText("{}")

        viewModel.deleteExportFile(file)

        verify(mockLocalRepo, never()).deleteExport(any(), any())
    }

    @Test
    fun `shareExportFile shows error message when share fails`() {
        val file = File(tempDir, "health_2026-05-24.json")
        file.writeText("{}")
        // getUriForFile will throw because FileProvider is not configured on the mock app
        whenever(mockApp.getString(R.string.share_file_error)).thenReturn("share error")

        viewModel.shareExportFile(file)

        assertEquals("share error", viewModel.uiState.value.message)
    }

    // =============================================
    // Helper: silent sign-in task mock
    // =============================================

    /**
     * Stubs googleSignInClient.silentSignIn() to return a mock Task whose
     * listeners can be captured and fired manually. Real Google Tasks require
     * a Looper, which plain-JVM Mockito tests don't have.
     */
    private fun silentSignInTask(): Task<GoogleSignInAccount> {
        val task = mock<Task<GoogleSignInAccount>>()
        whenever(task.addOnSuccessListener(any<OnSuccessListener<GoogleSignInAccount>>()))
            .thenReturn(task)
        whenever(task.addOnFailureListener(any<OnFailureListener>()))
            .thenReturn(task)
        whenever(mockGoogleSignInClient.silentSignIn()).thenReturn(task)
        return task
    }

    /**
     * Stubs googleSignInClient.signOut() to return a mock Task whose completion
     * listener can be captured and fired manually (real Google Tasks require
     * a Looper, which plain-JVM Mockito tests don't have).
     */
    private fun signOutTask(): Task<Void> {
        val task = mock<Task<Void>>()
        whenever(task.addOnCompleteListener(any<OnCompleteListener<Void>>()))
            .thenReturn(task)
        whenever(mockGoogleSignInClient.signOut()).thenReturn(task)
        return task
    }

    // =============================================
    // Helper: run a block with test dispatcher
    // =============================================

    private fun runTest(testBody: suspend () -> Unit) {
        kotlinx.coroutines.test.runTest(testDispatcher) {
            testBody()
        }
    }
}
