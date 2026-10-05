package com.healthconnect.export.repository

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Regression tests for the day attribution used by [HealthConnectRepository.readPeriodInBatch].
 *
 * Health Connect returns every interval record that merely *overlaps* the requested
 * range, not only those starting inside it. The batch reader pre-seeds its day map
 * with exactly the requested days, so attributing an overlapping record to its own
 * start day used to abort the export with an IllegalArgumentException. That only
 * surfaced in the background workers, which read a single day at a time — manual
 * exports use a multi-day window where the start day was usually still present.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HealthConnectRepositoryTest {
    private val zone: ZoneId = ZoneId.systemDefault()
    private val repo = HealthConnectRepository(mock<Context>())

    private fun instant(
        date: LocalDate,
        hour: Int,
        minute: Int = 0,
    ): Instant = LocalDateTime.of(date, java.time.LocalTime.of(hour, minute)).atZone(zone).toInstant()

    @Test
    fun `record starting inside the window keeps its own day`() {
        val day = LocalDate.of(2026, 5, 23)

        val result = repo.clampToWindow(instant(day, 14, 30), day, day)

        assertEquals(day, result)
    }

    @Test
    fun `overnight session starting the day before is clamped to window start`() {
        // The exact case that broke background export: a sleep session running
        // 23:00 -> 07:00 is returned for a single-day window starting at 00:00,
        // while its start day is not part of the day map.
        val day = LocalDate.of(2026, 5, 23)

        val result = repo.clampToWindow(instant(day.minusDays(1), 23), day, day)

        assertEquals(day, result)
    }

    @Test
    fun `record starting after the window is clamped to window end`() {
        val day = LocalDate.of(2026, 5, 23)

        val result = repo.clampToWindow(instant(day.plusDays(1), 2), day, day)

        assertEquals(day, result)
    }

    @Test
    fun `multi-day window clamps only the out-of-range records`() {
        val start = LocalDate.of(2026, 5, 20)
        val end = LocalDate.of(2026, 5, 23)

        assertEquals(start, repo.clampToWindow(instant(start.minusDays(1), 23), start, end))
        assertEquals(LocalDate.of(2026, 5, 21), repo.clampToWindow(instant(LocalDate.of(2026, 5, 21), 3), start, end))
        assertEquals(end, repo.clampToWindow(instant(end.plusDays(1), 1), start, end))
    }

    @Test
    fun `window boundaries are inclusive on both ends`() {
        val start = LocalDate.of(2026, 5, 20)
        val end = LocalDate.of(2026, 5, 23)

        assertEquals(start, repo.clampToWindow(instant(start, 0), start, end))
        assertEquals(end, repo.clampToWindow(instant(end, 23, 59), start, end))
    }
}
