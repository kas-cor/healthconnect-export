package com.healthconnect.export.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvMapperTest {
    private fun record(
        date: String = "2026-05-24",
        steps: StepsData? = null,
        heartRate: HeartRateData? = null,
        sleep: SleepData? = null,
        exercises: List<ExerciseData>? = null,
        nutrition: List<NutritionData>? = null,
        menstruation: MenstruationData? = null,
        metadata: ExportMetadata = ExportMetadata("1.0.0", "2026-05-24T23:00:00", "Europe/Moscow", "test_device"),
    ) = DailyHealthRecord(
        date = date,
        steps = steps,
        heartRate = heartRate,
        sleep = sleep,
        exercises = exercises,
        nutrition = nutrition,
        menstruation = menstruation,
        metadata = metadata,
    )

    // ============================
    // header
    // ============================

    @Test
    fun `header starts with date and ends with metadata source device`() {
        val header = CsvMapper.header()
        val columns = header.split(",")
        assertEquals("date", columns.first())
        assertEquals("metadata_source_device", columns.last())
    }

    @Test
    fun `header column count matches record row cell count`() {
        val row = CsvMapper.recordToCsv(record())
        val headerCells = CsvMapper.header().split(",").size
        val rowCells = row.split(",").size
        assertEquals(
            "Header and row must have the same number of cells",
            headerCells,
            rowCells,
        )
    }

    // ============================
    // recordToCsv
    // ============================

    @Test
    fun `recordToCsv puts date in first cell`() {
        val row = CsvMapper.recordToCsv(record(date = "2026-05-24"))
        assertTrue(row.startsWith("2026-05-24,"))
    }

    @Test
    fun `recordToCsv writes steps and heart rate values`() {
        val row =
            CsvMapper.recordToCsv(
                record(
                    steps = StepsData(totalSteps = 12453, recordsCount = 480),
                    heartRate = HeartRateData(avgBpm = 72.5, minBpm = 55, maxBpm = 142, recordsCount = 18),
                ),
            )
        assertTrue(row.contains("12453"))
        assertTrue(row.contains("480"))
        assertTrue(row.contains("72.5"))
        assertTrue(row.contains("142"))
    }

    @Test
    fun `recordToCsv leaves empty cells for missing sections`() {
        val row = CsvMapper.recordToCsv(record())
        // No steps data → the steps_total/steps_records cells must be empty.
        // "2026-05-24,," means the first two data cells are blank.
        assertTrue("Expected empty cells after date, got: $row", row.startsWith("2026-05-24,,"))
    }

    @Test
    fun `recordToCsv writes metadata values at the end`() {
        val row = CsvMapper.recordToCsv(record())
        assertTrue(row.endsWith("1.0.0,2026-05-24T23:00:00,Europe/Moscow,test_device"))
    }

    @Test
    fun `recordToCsv escapes commas inside values`() {
        val menstruation = MenstruationData(flowType = "Medium, heavy", time = "2026-05-24T08:00:00")
        val row = CsvMapper.recordToCsv(record(menstruation = menstruation))
        // Flow type with a comma must be quoted
        assertTrue(row.contains("\"Medium, heavy\""))
    }

    @Test
    fun `recordToCsv escapes quotes by doubling them`() {
        val menstruation = MenstruationData(flowType = "Medium \"spotting\"", time = "2026-05-24T08:00:00")
        val row = CsvMapper.recordToCsv(record(menstruation = menstruation))
        assertTrue(row.contains("\"Medium \"\"spotting\"\"\""))
    }

    @Test
    fun `recordToCsv formats whole doubles without trailing zeros`() {
        val row =
            CsvMapper.recordToCsv(
                record(
                    steps = StepsData(totalSteps = 100, recordsCount = 1),
                    heartRate = HeartRateData(avgBpm = 72.0, minBpm = 55, maxBpm = 142, recordsCount = 1),
                ),
            )
        assertTrue(row.contains("72"))
        assertTrue(row.contains(",55,"))
    }

    @Test
    fun `recordToCsv writes exercises and nutrition counts`() {
        val row =
            CsvMapper.recordToCsv(
                record(
                    exercises =
                        listOf(
                            ExerciseData(
                                exerciseType = "Running",
                                startTime = "2026-05-24T10:00:00",
                                endTime = "2026-05-24T10:30:00",
                                durationMinutes = 30,
                            ),
                            ExerciseData(
                                exerciseType = "Yoga",
                                startTime = "2026-05-24T18:00:00",
                                endTime = "2026-05-24T19:00:00",
                                durationMinutes = 60,
                            ),
                        ),
                    nutrition =
                        listOf(
                            NutritionData(name = "Apple", energyKcal = 52.0),
                        ),
                ),
            )
        assertTrue(row.contains("2")) // exercises count
        assertTrue(row.contains("1")) // nutrition count
    }

    @Test
    fun `recordToCsv writes menstruation flow and time`() {
        val menstruation = MenstruationData(flowType = "Medium", time = "2026-05-24T08:00:00")
        val row = CsvMapper.recordToCsv(record(menstruation = menstruation))
        assertTrue(row.contains("Medium"))
        assertTrue(row.contains("2026-05-24T08:00:00"))
    }

    @Test
    fun `recordToCsv handles newline in a value`() {
        val menstruation = MenstruationData(flowType = "Line1\nLine2", time = "2026-05-24T08:00:00")
        val row = CsvMapper.recordToCsv(record(menstruation = menstruation))
        assertTrue(row.contains("\"Line1\nLine2\""))
    }

    @Test
    fun `header and row both have the same value for a full record`() {
        val full =
            record(
                steps = StepsData(totalSteps = 12453, recordsCount = 480),
                heartRate = HeartRateData(avgBpm = 72.5, minBpm = 55, maxBpm = 142, recordsCount = 18),
                sleep = SleepData(totalDurationMinutes = 420, sleepStages = mapOf("Deep" to 90L), recordsCount = 1),
            )
        val headerCells = CsvMapper.header().split(",")
        val rowCells = CsvMapper.recordToCsv(full).split(",")
        assertEquals(headerCells.size, rowCells.size)
    }
}
