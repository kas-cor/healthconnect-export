package com.healthconnect.export.usecase

import android.content.Context
import com.healthconnect.export.data.*
import com.healthconnect.export.repository.HealthConnectRepository
import com.healthconnect.export.repository.LocalExportRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.*
import java.io.File
import java.time.LocalDate

@RunWith(MockitoJUnitRunner.Silent::class)
class ExportDataUseCaseTest {

    @Mock
    private lateinit var mockHealthRepo: HealthConnectRepository

    @Mock
    private lateinit var mockLocalRepo: LocalExportRepository

    @Mock
    private lateinit var mockContext: Context

    private lateinit var useCase: ExportDataUseCase

    private val defaultConfig = ExportConfig(
        enabledTypes = setOf(HealthDataType.STEPS, HealthDataType.HEART_RATE),
        frequency = ExportFrequency.DAILY,
        autoSyncDrive = false,
        outputDirectory = "HealthConnectExport"
    )

    private val startDate = LocalDate.of(2026, 5, 24)
    private val endDate = LocalDate.of(2026, 5, 25)

    @Before
    fun setup() {
        useCase = ExportDataUseCase(mockHealthRepo, mockLocalRepo)
    }

    /** Helper: create a minimal DailyHealthRecord for a given date */
    private fun recordForDate(dateStr: String): DailyHealthRecord =
        DailyHealthRecord(
            date = dateStr,
            metadata = ExportMetadata(
                appVersion = "1.0.0",
                exportTimestamp = "${dateStr}T12:00:00",
                timezone = "UTC"
            )
        )

    /** Assert that a given step is a Progress with the expected phase/current/total/dateOrType */
    private fun assertProgress(
        step: ExportStep,
        expectedPhase: String,
        expectedCurrent: Int,
        expectedTotal: Int,
        expectedDateOrType: String
    ) {
        assertTrue("Expected Progress, got ${step::class.simpleName}", step is ExportStep.Progress)
        val msg = (step as ExportStep.Progress).message
        val parts = msg.split(":")
        assertEquals(expectedPhase, parts[0])
        assertEquals(expectedCurrent.toString(), parts[1])
        assertEquals(expectedTotal.toString(), parts[2])
        assertEquals(expectedDateOrType, parts[3])
    }

    // ============================
    // Successful Export
    // ============================

    @Test
    fun `successful export emits Complete with records and files`() {
        runBlocking {
            val record = recordForDate("2026-05-24")
            val record2 = recordForDate("2026-05-25")
            val files = listOf(File("health_2026-05-24.json"), File("health_2026-05-25.json"))

            whenever(mockHealthRepo.isHealthConnectAvailable()).thenReturn(true)
            whenever(mockHealthRepo.checkPermissions(any())).thenReturn(true)
            // readPeriodInBatch returns all records for the full period
            whenever(mockHealthRepo.readPeriodInBatch(any(), any(), any(), anyOrNull(), anyOrNull()))
                .thenReturn(listOf(record, record2))
            whenever(mockLocalRepo.saveDailyRecord(any(), any())).thenReturn(files[0], files[1])

            val steps = useCase.execute(mockContext, defaultConfig, startDate, endDate).toList()

            // 1 checking + 2 save progress + 1 complete = 4
            assertEquals(4, steps.size)
            assertTrue(steps[0] is ExportStep.CheckingPermissions)
            assertProgress(steps[1], "save", 1, 2, "2026-05-24")
            assertProgress(steps[2], "save", 2, 2, "2026-05-25")
            assertTrue(steps[3] is ExportStep.Complete)
            val complete = steps[3] as ExportStep.Complete
            assertEquals(2, complete.records.size)
            assertEquals(2, complete.files.size)
            assertEquals(files, complete.files)
        }
    }

    // ============================
    // Health Connect Not Installed
    // ============================

    @Test
    fun `when health not installed emits HealthNotInstalled`() {
        runBlocking {
            whenever(mockHealthRepo.isHealthConnectAvailable()).thenReturn(false)
            whenever(mockHealthRepo.isHealthConnectInstalled()).thenReturn(false)

            val steps = useCase.execute(mockContext, defaultConfig, startDate, endDate).toList()

            assertEquals(2, steps.size)
            assertTrue(steps[0] is ExportStep.CheckingPermissions)
            assertTrue(steps[1] is ExportStep.HealthNotInstalled)
        }
    }

    // ============================
    // Health Connect Not Available but Installed
    // ============================

    @Test
    fun `when health not available emits HealthNotAvailable`() {
        runBlocking {
            whenever(mockHealthRepo.isHealthConnectAvailable()).thenReturn(false)
            whenever(mockHealthRepo.isHealthConnectInstalled()).thenReturn(true)

            val steps = useCase.execute(mockContext, defaultConfig, startDate, endDate).toList()

            assertEquals(2, steps.size)
            assertTrue(steps[0] is ExportStep.CheckingPermissions)
            assertTrue(steps[1] is ExportStep.HealthNotAvailable)
        }
    }

    // ============================
    // Permissions Required
    // ============================

    @Test
    fun `when permissions missing emits PermissionsRequired`() {
        runBlocking {
            val requiredPermissions = setOf("health_read_steps", "health_read_heart_rate")

            whenever(mockHealthRepo.isHealthConnectAvailable()).thenReturn(true)
            whenever(mockHealthRepo.checkPermissions(any())).thenReturn(false)
            whenever(mockHealthRepo.getPermissionsForTypes(any())).thenReturn(requiredPermissions)

            val steps = useCase.execute(mockContext, defaultConfig, startDate, endDate).toList()

            assertEquals(2, steps.size)
            assertTrue(steps[0] is ExportStep.CheckingPermissions)
            assertTrue(steps[1] is ExportStep.PermissionsRequired)
            assertEquals(requiredPermissions, (steps[1] as ExportStep.PermissionsRequired).permissions)
        }
    }

    // ============================
    // Exception Handling
    // ============================

    @Test
    fun `when exception occurs emits Error`() {
        runBlocking {
            whenever(mockHealthRepo.isHealthConnectAvailable()).thenReturn(true)
            whenever(mockHealthRepo.checkPermissions(any())).thenReturn(true)
            whenever(mockHealthRepo.readPeriodInBatch(any(), any(), any(), anyOrNull(), anyOrNull()))
                .thenThrow(RuntimeException("Network failure"))

            val steps = useCase.execute(mockContext, defaultConfig, startDate, endDate).toList()

            // 1 checking + error = 2
            assertEquals(2, steps.size)
            assertTrue(steps[0] is ExportStep.CheckingPermissions)
            assertTrue(steps[1] is ExportStep.Error)
            assertEquals("Network failure", (steps[1] as ExportStep.Error).message)
        }
    }

    // ============================
    // Empty Records
    // ============================

    @Test
    fun `when no data emits Complete with empty records`() {
        runBlocking {
            // Empty record: healthRepo returns records with no data, then no files are saved
            val emptyRecord = DailyHealthRecord(
                date = "2026-05-24",
                metadata = ExportMetadata("1.0.0", "2026-05-24T12:00:00", "UTC")
            )
            val emptyRecord2 = DailyHealthRecord(
                date = "2026-05-25",
                metadata = ExportMetadata("1.0.0", "2026-05-25T12:00:00", "UTC")
            )

            whenever(mockHealthRepo.isHealthConnectAvailable()).thenReturn(true)
            whenever(mockHealthRepo.checkPermissions(any())).thenReturn(true)
            whenever(mockHealthRepo.readPeriodInBatch(any(), any(), any(), anyOrNull(), anyOrNull()))
                .thenReturn(listOf(emptyRecord, emptyRecord2))
            whenever(mockLocalRepo.saveDailyRecord(any(), any())).thenReturn(File("health_2026-05-24.json"), File("health_2026-05-25.json"))

            val steps = useCase.execute(mockContext, defaultConfig, startDate, endDate).toList()

            // 1 checking + 2 save progress + 1 complete = 4
            assertEquals(4, steps.size)
            assertTrue(steps.last() is ExportStep.Complete)
            val complete = steps.last() as ExportStep.Complete
            assertEquals(2, complete.records.size)
            assertEquals(2, complete.files.size)
            assertEquals(2, complete.summary.daysCount) // daysCount = records.size regardless of data
        }
    }

    // ============================
    // Summary Calculation
    // ============================

    @Test
    fun `summary is correctly computed from records`() {
        runBlocking {
            val record1 = DailyHealthRecord(
                date = "2026-05-24",
                steps = StepsData(totalSteps = 5000, recordsCount = 100),
                heartRate = HeartRateData(avgBpm = 72.0, minBpm = 55, maxBpm = 140, recordsCount = 10),
                calories = CaloriesData(totalCalories = 200.0, recordsCount = 5),
                distance = DistanceData(totalDistanceMeters = 3000.0, recordsCount = 3),
                sleep = SleepData(totalDurationMinutes = 420, sleepStages = mapOf("Deep" to 90), recordsCount = 1),
                activeCalories = ActiveCaloriesData(totalCalories = 150.0, recordsCount = 5),
                metadata = ExportMetadata("1.0.0", "2026-05-24T12:00:00", "UTC")
            )

            val record2 = DailyHealthRecord(
                date = "2026-05-25",
                steps = StepsData(totalSteps = 8000, recordsCount = 150),
                heartRate = HeartRateData(avgBpm = 68.0, minBpm = 50, maxBpm = 135, recordsCount = 12),
                calories = CaloriesData(totalCalories = 250.0, recordsCount = 6),
                distance = DistanceData(totalDistanceMeters = 5000.0, recordsCount = 4),
                sleep = SleepData(totalDurationMinutes = 360, sleepStages = mapOf("Light" to 180), recordsCount = 1),
                activeCalories = ActiveCaloriesData(totalCalories = 200.0, recordsCount = 6),
                metadata = ExportMetadata("1.0.0", "2026-05-25T12:00:00", "UTC")
            )

            val files = listOf(
                File("health_2026-05-24.json"),
                File("health_2026-05-25.json")
            )

            whenever(mockHealthRepo.isHealthConnectAvailable()).thenReturn(true)
            whenever(mockHealthRepo.checkPermissions(any())).thenReturn(true)
            whenever(mockHealthRepo.readPeriodInBatch(any(), any(), any(), anyOrNull(), anyOrNull()))
                .thenReturn(listOf(record1, record2))
            whenever(mockLocalRepo.saveDailyRecord(any(), any())).thenReturn(files[0], files[1])

            val steps = useCase.execute(mockContext, defaultConfig, startDate, endDate).toList()

            val complete = steps.last() as ExportStep.Complete
            val summary = complete.summary

            assertEquals(13000L, summary.totalSteps)
            assertEquals(70.0, summary.avgHeartRate, 0.001)
            assertEquals(450.0, summary.totalCalories, 0.001)
            assertEquals(8000.0, summary.totalDistanceMeters, 0.001)
            assertEquals(390L, summary.avgSleepMinutes) // (420 + 360) / 2
            assertEquals(350.0, summary.totalActiveCalories, 0.001)
            assertEquals(2, summary.daysCount)
            assertEquals("2026-05-24", summary.startDate)
            assertEquals("2026-05-25", summary.endDate)
        }
    }

    // ============================
    // Summary with Missing Fields
    // ============================

    @Test
    fun `summary handles null fields correctly`() {
        runBlocking {
            val record1 = recordForDate("2026-05-24").copy(
                steps = StepsData(totalSteps = 5000, recordsCount = 100)
            )
            val record2 = recordForDate("2026-05-25")

            whenever(mockHealthRepo.isHealthConnectAvailable()).thenReturn(true)
            whenever(mockHealthRepo.checkPermissions(any())).thenReturn(true)
            whenever(mockHealthRepo.readPeriodInBatch(any(), any(), any(), anyOrNull(), anyOrNull()))
                .thenReturn(listOf(record1, record2))
            whenever(mockLocalRepo.saveDailyRecord(any(), any())).thenReturn(File("h.json"), File("h2.json"))

            val steps = useCase.execute(mockContext, defaultConfig, startDate, endDate).toList()

            val complete = steps.last() as ExportStep.Complete
            val summary = complete.summary

            assertEquals(5000L, summary.totalSteps)
            assertEquals(0.0, summary.avgHeartRate, 0.001)
            assertEquals(0.0, summary.totalCalories, 0.001)
            assertEquals(0.0, summary.totalDistanceMeters, 0.001)
            assertEquals(0L, summary.avgSleepMinutes)
            assertEquals(0.0, summary.totalActiveCalories, 0.001)
            assertEquals(2, summary.daysCount)
        }
    }

    // ============================
    // Summary with Mixed Null Fields (branch coverage)
    // ============================

    @Test
    fun `summary with mixed null fields covers both branches`() {
        runBlocking {
            val record1 = DailyHealthRecord(
                date = "2026-05-24",
                heartRate = HeartRateData(avgBpm = 72.0, minBpm = 55, maxBpm = 140, recordsCount = 10),
                calories = CaloriesData(totalCalories = 200.0, recordsCount = 5),
                activeCalories = ActiveCaloriesData(totalCalories = 150.0, recordsCount = 5),
                metadata = ExportMetadata("1.0.0", "2026-05-24T12:00:00", "UTC")
            )

            val record2 = DailyHealthRecord(
                date = "2026-05-25",
                steps = StepsData(totalSteps = 8000, recordsCount = 150),
                sleep = SleepData(totalDurationMinutes = 360, sleepStages = mapOf("Light" to 180), recordsCount = 1),
                distance = DistanceData(totalDistanceMeters = 5000.0, recordsCount = 4),
                metadata = ExportMetadata("1.0.0", "2026-05-25T12:00:00", "UTC")
            )

            val files = listOf(File("h1.json"), File("h2.json"))

            whenever(mockHealthRepo.isHealthConnectAvailable()).thenReturn(true)
            whenever(mockHealthRepo.checkPermissions(any())).thenReturn(true)
            whenever(mockHealthRepo.readPeriodInBatch(any(), any(), any(), anyOrNull(), anyOrNull()))
                .thenReturn(listOf(record1, record2))
            whenever(mockLocalRepo.saveDailyRecord(any(), any())).thenReturn(files[0], files[1])

            val steps = useCase.execute(mockContext, defaultConfig, startDate, endDate).toList()

            val complete = steps.last() as ExportStep.Complete
            val summary = complete.summary

            assertEquals(8000L, summary.totalSteps)
            assertEquals(72.0, summary.avgHeartRate, 0.001)
            assertEquals(200.0, summary.totalCalories, 0.001)
            assertEquals(5000.0, summary.totalDistanceMeters, 0.001)
            assertEquals(360L, summary.avgSleepMinutes)
            assertEquals(150.0, summary.totalActiveCalories, 0.001)
            assertEquals(2, summary.daysCount)
        }
    }

    // ============================
    // Exception with null message
    // ============================

    @Test
    fun `exception with null message shows default error text`() {
        runBlocking {
            whenever(mockHealthRepo.isHealthConnectAvailable()).thenReturn(true)
            whenever(mockHealthRepo.checkPermissions(any())).thenReturn(true)
            whenever(mockHealthRepo.readPeriodInBatch(any(), any(), any(), anyOrNull(), anyOrNull()))
                .thenThrow(RuntimeException())

            val steps = useCase.execute(mockContext, defaultConfig, startDate, endDate).toList()

            // 1 checking + error = 2
            assertEquals(2, steps.size)
            val error = steps.last() as ExportStep.Error
            assertEquals("Unknown error", error.message)
        }
    }

    // ============================
    // selectedSourcePackage forwarding
    // ============================

    @Test
    fun `selectedSourcePackage is forwarded to readPeriodInBatch`() {
        runBlocking {
            val configWithSource = defaultConfig.copy(selectedSourcePackage = "com.mi.health")
            val record = recordForDate("2026-05-24")
            val record2 = recordForDate("2026-05-25")

            whenever(mockHealthRepo.isHealthConnectAvailable()).thenReturn(true)
            whenever(mockHealthRepo.checkPermissions(any())).thenReturn(true)
            whenever(mockHealthRepo.readPeriodInBatch(any(), any(), any(), anyOrNull(), anyOrNull()))
                .thenReturn(listOf(record, record2))
            whenever(mockLocalRepo.saveDailyRecord(any(), any())).thenReturn(File("h.json"), File("h2.json"))

            val steps = useCase.execute(mockContext, configWithSource, startDate, endDate).toList()

            assertTrue(steps.last() is ExportStep.Complete)
            // readPeriodInBatch should be called with the selectedSourcePackage
            verify(mockHealthRepo).readPeriodInBatch(any(), any(), any(), eq("com.mi.health"), anyOrNull())
        }
    }

    @Test
    fun `null selectedSourcePackage is forwarded as null to readPeriodInBatch`() {
        runBlocking {
            val configWithNullSource = defaultConfig.copy(selectedSourcePackage = null)
            val record = recordForDate("2026-05-24")
            val record2 = recordForDate("2026-05-25")

            whenever(mockHealthRepo.isHealthConnectAvailable()).thenReturn(true)
            whenever(mockHealthRepo.checkPermissions(any())).thenReturn(true)
            whenever(mockHealthRepo.readPeriodInBatch(any(), any(), any(), isNull(), anyOrNull()))
                .thenReturn(listOf(record, record2))
            whenever(mockLocalRepo.saveDailyRecord(any(), any())).thenReturn(File("h.json"), File("h2.json"))

            val steps = useCase.execute(mockContext, configWithNullSource, startDate, endDate).toList()

            assertTrue(steps.last() is ExportStep.Complete)
            verify(mockHealthRepo).readPeriodInBatch(any(), any(), any(), isNull(), anyOrNull())
        }
    }

    @Test
    fun `selectedSourcePackage with permissions missing still triggers correct flow`() {
        runBlocking {
            val configWithSource = defaultConfig.copy(selectedSourcePackage = "com.samsung.samsunghealth")
            val requiredPermissions = setOf("health_read_steps")

            whenever(mockHealthRepo.isHealthConnectAvailable()).thenReturn(true)
            whenever(mockHealthRepo.checkPermissions(any())).thenReturn(false)
            whenever(mockHealthRepo.getPermissionsForTypes(any())).thenReturn(requiredPermissions)

            val steps = useCase.execute(mockContext, configWithSource, startDate, endDate).toList()

            assertEquals(2, steps.size)
            assertTrue(steps[0] is ExportStep.CheckingPermissions)
            assertTrue(steps[1] is ExportStep.PermissionsRequired)
            // readPeriodInBatch should never be called since permissions are missing
            verify(mockHealthRepo, never()).readPeriodInBatch(any(), any(), any(), anyOrNull(), anyOrNull())
        }
    }

    // ============================
    // Complete step — summary edge cases
    // ============================

    @Test
    fun `complete step contains correct fields`() {
        runBlocking {
            val record = recordForDate("2026-05-24").copy(
                steps = StepsData(totalSteps = 7500, recordsCount = 200),
                heartRate = HeartRateData(avgBpm = 75.0, minBpm = 60, maxBpm = 150, recordsCount = 15),
                calories = CaloriesData(totalCalories = 300.0, recordsCount = 8),
                distance = DistanceData(totalDistanceMeters = 4500.0, recordsCount = 5),
                sleep = SleepData(totalDurationMinutes = 400, sleepStages = mapOf("Deep" to 100), recordsCount = 1),
                activeCalories = ActiveCaloriesData(totalCalories = 180.0, recordsCount = 6),
                metadata = ExportMetadata("1.0.0", "2026-05-24T12:00:00", "UTC")
            )
            val record2 = recordForDate("2026-05-25")
            val files = listOf(File("health_2026-05-24.json"), File("health_2026-05-25.json"))

            whenever(mockHealthRepo.isHealthConnectAvailable()).thenReturn(true)
            whenever(mockHealthRepo.checkPermissions(any())).thenReturn(true)
            whenever(mockHealthRepo.readPeriodInBatch(any(), any(), any(), anyOrNull(), anyOrNull()))
                .thenReturn(listOf(record, record2))
            whenever(mockLocalRepo.saveDailyRecord(any(), any())).thenReturn(files[0], files[1])

            val steps = useCase.execute(mockContext, defaultConfig, startDate, endDate).toList()

            val complete = steps.last() as ExportStep.Complete
            assertEquals(2, complete.records.size)
            assertEquals(files, complete.files)
            assertNotNull(complete.summary)
            assertEquals(2, complete.summary.daysCount)
        }
    }

    // ============================
    // Progress Messages
    // ============================

    @Test
    fun `progress messages are emitted in correct order`() {
        runBlocking {
            val record1 = recordForDate("2026-05-24")
            val record2 = recordForDate("2026-05-25")
            val record3 = recordForDate("2026-05-26")
            val threeDaysStart = LocalDate.of(2026, 5, 24)
            val threeDaysEnd = LocalDate.of(2026, 5, 26)

            whenever(mockHealthRepo.isHealthConnectAvailable()).thenReturn(true)
            whenever(mockHealthRepo.checkPermissions(any())).thenReturn(true)
            whenever(mockHealthRepo.readPeriodInBatch(any(), any(), any(), anyOrNull(), anyOrNull()))
                .thenReturn(listOf(record1, record2, record3))
            whenever(mockLocalRepo.saveDailyRecord(any(), any())).thenReturn(
                File("h1.json"), File("h2.json"), File("h3.json")
            )

            val steps = useCase.execute(mockContext, defaultConfig, threeDaysStart, threeDaysEnd).toList()

            // 1 checking + 3 save + 1 complete = 5
            assertEquals(5, steps.size)
            val progressSteps = steps.filterIsInstance<ExportStep.Progress>()

            // Save phases (per day)
            assertEquals("save", progressSteps[0].message.split(":")[0])
            assertEquals("1", progressSteps[0].message.split(":")[1])
            assertEquals("3", progressSteps[0].message.split(":")[2])
            assertEquals("2026-05-24", progressSteps[0].message.split(":")[3])

            assertEquals("save", progressSteps[1].message.split(":")[0])
            assertEquals("2", progressSteps[1].message.split(":")[1])
            assertEquals("3", progressSteps[1].message.split(":")[2])
            assertEquals("2026-05-25", progressSteps[1].message.split(":")[3])

            assertEquals("save", progressSteps[2].message.split(":")[0])
            assertEquals("3", progressSteps[2].message.split(":")[1])
            assertEquals("3", progressSteps[2].message.split(":")[2])
            assertEquals("2026-05-26", progressSteps[2].message.split(":")[3])
        }
    }

    // ============================
    // Single day export
    // ============================

    @Test
    fun `single day export emits correct progress`() {
        runBlocking {
            val singleDayStart = LocalDate.of(2026, 5, 24)
            val singleDayEnd = LocalDate.of(2026, 5, 24)
            val record = recordForDate("2026-05-24")
            val file = File("health_2026-05-24.json")

            whenever(mockHealthRepo.isHealthConnectAvailable()).thenReturn(true)
            whenever(mockHealthRepo.checkPermissions(any())).thenReturn(true)
            whenever(mockHealthRepo.readPeriodInBatch(any(), any(), any(), anyOrNull(), anyOrNull()))
                .thenReturn(listOf(record))
            whenever(mockLocalRepo.saveDailyRecord(any(), any())).thenReturn(file)

            val steps = useCase.execute(mockContext, defaultConfig, singleDayStart, singleDayEnd).toList()

            // 1 checking + 1 save + 1 complete = 3
            assertEquals(3, steps.size)
            assertProgress(steps[1], "save", 1, 1, "2026-05-24")
            assertTrue(steps[2] is ExportStep.Complete)
        }
    }
}
