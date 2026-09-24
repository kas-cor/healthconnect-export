package com.healthconnect.export.util

import android.app.Application
import android.content.Context
import com.healthconnect.export.testing.FakeSharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@RunWith(MockitoJUnitRunner.Silent::class)
class DeliveryLogTest {
    private lateinit var context: Application
    private lateinit var prefs: FakeSharedPreferences

    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    @Before
    fun setup() {
        prefs = FakeSharedPreferences()
        context = mock()
        whenever(context.getSharedPreferences(any(), any())).thenReturn(prefs)
    }

    private fun fixedNow(hours: Int): Long = 1_700_000_000_000L + hours * 3_600_000L

    @Test
    fun `empty status has no timestamps`() {
        val status = DeliveryLog.status(context)

        assertNull(status.lastAttemptAt)
        assertNull(status.lastAttemptResult)
        assertNull(status.lastSuccessAt)
        assertNull(status.lastDataDate)
        assertTrue(DeliveryLog.entries(context).isEmpty())
    }

    @Test
    fun `success stores the newest delivered day as the resume point`() {
        val now = fixedNow(1)
        DeliveryLog.recordSuccess(context, "watchdog", listOf("2026-09-20", "2026-09-22", "2026-09-21"), now)

        val status = DeliveryLog.status(context)
        assertEquals(now, status.lastAttemptAt)
        assertEquals(now, status.lastSuccessAt)
        assertEquals(LocalDate.of(2026, 9, 22), status.lastDataDate)
        assertEquals("ok: 3 day(s) up to 2026-09-22", status.lastAttemptResult)
        assertEquals(LocalDate.of(2026, 9, 22), DeliveryLog.lastDataDate(context))
    }

    @Test
    fun `success without days keeps the previous resume point`() {
        DeliveryLog.recordSuccess(context, "seed", listOf("2026-09-01"), fixedNow(0))
        DeliveryLog.recordSuccess(context, "daily", emptyList(), fixedNow(5))

        val status = DeliveryLog.status(context)
        assertEquals(LocalDate.of(2026, 9, 1), status.lastDataDate)
        assertEquals("ok: no data", status.lastAttemptResult)
        assertEquals(fixedNow(5), status.lastSuccessAt)
    }

    @Test
    fun `failure keeps the resume point and the last success`() {
        DeliveryLog.recordSuccess(context, "daily", listOf("2026-09-05"), fixedNow(0))
        DeliveryLog.recordFailure(context, "every-2h", "HTTP 500: boom", fixedNow(2))

        val status = DeliveryLog.status(context)
        assertEquals(LocalDate.of(2026, 9, 5), status.lastDataDate)
        assertEquals(fixedNow(0), status.lastSuccessAt)
        assertEquals("failed: HTTP 500: boom", status.lastAttemptResult)
        assertEquals(fixedNow(2), status.lastAttemptAt)
    }

    @Test
    fun `idle runs are recorded as such`() {
        DeliveryLog.recordIdle(context, "catch-up", "up to date", fixedNow(3))

        val status = DeliveryLog.status(context)
        assertEquals("idle: up to date", status.lastAttemptResult)
        assertNull(status.lastSuccessAt)
    }

    @Test
    fun `entries are newest first and contain the trigger`() {
        DeliveryLog.recordSuccess(context, "daily", listOf("2026-09-01"), fixedNow(1))
        DeliveryLog.recordFailure(context, "watchdog", "boom", fixedNow(2))

        val entries = DeliveryLog.entries(context)
        assertEquals(2, entries.size)
        assertTrue(entries[0].startsWith(formatter.format(java.time.Instant.ofEpochMilli(fixedNow(2)))))
        assertTrue(entries[0].contains("[watchdog] failed: boom"))
        assertTrue(entries[1].contains("[daily] ok: 1 day(s) up to 2026-09-01"))
    }

    @Test
    fun `log keeps only the newest entries`() {
        repeat(DeliveryLog.MAX_ENTRIES + 5) { index ->
            DeliveryLog.recordIdle(context, "run-$index", "tick", fixedNow(index))
        }

        val entries = DeliveryLog.entries(context)
        assertEquals(DeliveryLog.MAX_ENTRIES, entries.size)
        assertTrue(entries.first().contains("[run-${DeliveryLog.MAX_ENTRIES + 4}]"))
    }

    @Test
    fun `clear removes the log entries`() {
        DeliveryLog.recordIdle(context, "daily", "tick", fixedNow(0))
        DeliveryLog.clear(context)

        assertTrue(DeliveryLog.entries(context).isEmpty())
    }

    @Test
    fun `unreadable stored date is ignored`() {
        prefs.edit().putString("delivery_last_data_date", "not-a-date").apply()

        assertNull(DeliveryLog.lastDataDate(context))
    }

    @Test
    fun `status works with a plain context`() {
        val plainContext = mock<Context>()
        whenever(plainContext.getSharedPreferences(any(), any())).thenReturn(FakeSharedPreferences())

        assertNull(DeliveryLog.status(plainContext).lastAttemptAt)
    }
}
