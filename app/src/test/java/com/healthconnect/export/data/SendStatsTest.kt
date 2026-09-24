package com.healthconnect.export.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SendStatsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val hourMs = 3_600_000L

    private fun record(date: String) =
        DailyHealthRecord(
            date = date,
            metadata = ExportMetadata("1.8", "2026-09-23T18:36:43", "Europe/Moscow"),
        )

    @Before
    fun clearPrefs() {
        context
            .getSharedPreferences("healthconnect_export_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `nothing recorded means no known last send`() {
        val last = SendStats.lastSend(context)

        assertNull(last.successTimestampMs)
        assertNull(last.successStatusCode)
        assertNull(last.lastSentDate)
        assertNull(last.hoursSinceSuccess())
        assertFalse("a device that never sent must not be reported stale", last.isStale(6))
    }

    @Test
    fun `successful send stores timestamp status code and newest record date`() {
        val now = 1_780_000_000_000L
        SendStats.recordSuccess(context, 200, listOf(record("2026-09-22"), record("2026-09-23")), now)

        val last = SendStats.lastSend(context)

        assertEquals(now, last.successTimestampMs)
        assertEquals(200, last.successStatusCode)
        assertEquals(java.time.LocalDate.of(2026, 9, 23), last.lastSentDate)
        assertEquals(0L, last.hoursSinceSuccess(now))
    }

    @Test
    fun `unparsable record dates are ignored`() {
        SendStats.recordSuccess(context, 202, listOf(record("not-a-date")), 1_780_000_000_000L)

        val last = SendStats.lastSend(context)

        assertEquals(202, last.successStatusCode)
        assertNull(last.lastSentDate)
    }

    @Test
    fun `stale boundary is inclusive and based on the last success`() {
        val now = 1_780_000_000_000L
        SendStats.recordSuccess(context, 200, listOf(record("2026-09-23")), now - 6 * hourMs)

        val last = SendStats.lastSend(context)

        assertEquals(6L, last.hoursSinceSuccess(now))
        assertTrue(last.isStale(6, now))
        assertFalse(last.isStale(7, now))
    }

    @Test
    fun `failure keeps the previous success untouched`() {
        val successAt = 1_780_000_000_000L
        SendStats.recordSuccess(context, 200, listOf(record("2026-09-23")), successAt)
        SendStats.recordFailure(context, 500, "server error", successAt + hourMs)

        val last = SendStats.lastSend(context)

        assertEquals(successAt, last.successTimestampMs)
        assertEquals(200, last.successStatusCode)
        assertEquals(successAt + hourMs, last.failureTimestampMs)
        assertEquals(500, last.failureStatusCode)
        assertEquals("server error", last.failureMessage)
        assertEquals(1L, last.hoursSinceSuccess(successAt + hourMs))
    }

    @Test
    fun `a later success clears the stale failure message`() {
        SendStats.recordFailure(context, 0, "timeout", 1_780_000_000_000L)
        SendStats.recordSuccess(context, 200, listOf(record("2026-09-23")), 1_780_000_100_000L)

        val last = SendStats.lastSend(context)

        assertNull(last.failureMessage)
        assertEquals(200, last.successStatusCode)
    }

    @Test
    fun `blank failure message is not reported`() {
        SendStats.recordFailure(context, 0, "   ", 1_780_000_000_000L)

        assertNull(SendStats.lastSend(context).failureMessage)
    }

    @Test
    fun `context without preferences reports an empty state instead of throwing`() {
        val last = SendStats.lastSend(org.mockito.kotlin.mock<Context>())

        assertNull(last.successTimestampMs)
        assertFalse(last.isStale(6))
    }
}
