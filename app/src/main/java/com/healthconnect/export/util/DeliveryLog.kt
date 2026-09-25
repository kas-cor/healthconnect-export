package com.healthconnect.export.util

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Persistent log of webhook delivery attempts.
 *
 * It serves two purposes:
 *  - **gap fill** — the newest day that was successfully delivered tells the
 *    catch-up job where to resume after a stalled period;
 *  - **diagnostics** — the last attempt/success and a short ring buffer of
 *    entries are shown in the UI, so a silent stall is visible in the app
 *    instead of only in an external alert.
 */
object DeliveryLog {
    private const val PREFS_NAME = "healthconnect_export_prefs"
    private const val KEY_LAST_ATTEMPT_AT = "delivery_last_attempt_at"
    private const val KEY_LAST_ATTEMPT_RESULT = "delivery_last_attempt_result"
    private const val KEY_LAST_SUCCESS_AT = "delivery_last_success_at"
    private const val KEY_LAST_DATA_DATE = "delivery_last_data_date"
    private const val KEY_ENTRIES = "delivery_log_entries"

    /** Maximum number of log lines kept in SharedPreferences. */
    const val MAX_ENTRIES = 100

    private val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    data class Status(
        val lastAttemptAt: Long? = null,
        val lastAttemptResult: String? = null,
        val lastSuccessAt: Long? = null,
        val lastDataDate: LocalDate? = null,
    )

    fun status(context: Context): Status {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Status(
            lastAttemptAt = prefs.getLong(KEY_LAST_ATTEMPT_AT, 0L).takeIf { it > 0L },
            lastAttemptResult = prefs.getString(KEY_LAST_ATTEMPT_RESULT, null),
            lastSuccessAt = prefs.getLong(KEY_LAST_SUCCESS_AT, 0L).takeIf { it > 0L },
            lastDataDate = prefs.getString(KEY_LAST_DATA_DATE, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        )
    }

    /** Newest day delivered to the webhook, or null when nothing was delivered yet. */
    fun lastDataDate(context: Context): LocalDate? = status(context).lastDataDate

    /** Log entries, newest first. */
    fun entries(context: Context): List<String> =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, "")
            ?.lineSequence()
            ?.filter { it.isNotBlank() }
            ?.toList()
            ?.asReversed()
            .orEmpty()

    /**
     * Records a successful delivery. [deliveredDates] are the ISO dates present in
     * the payload; the newest one becomes the resume point for the catch-up job.
     * When [statusCode] is known it becomes part of the recorded text, so the
     * Schedule tab shows "timestamp + code" for healthy deliveries too, not only
     * for failures.
     */
    fun recordSuccess(
        context: Context,
        trigger: String,
        deliveredDates: List<String>,
        now: Long = System.currentTimeMillis(),
        statusCode: Int? = null,
    ) {
        val newest = deliveredDates.maxOrNull()
        val detail =
            if (newest == null) {
                "no data"
            } else {
                "${deliveredDates.size} day(s) up to $newest"
            }
        val result = if (statusCode == null) "ok: $detail" else "HTTP $statusCode: $detail"
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs
            .edit()
            .putLong(KEY_LAST_ATTEMPT_AT, now)
            .putString(KEY_LAST_ATTEMPT_RESULT, result)
            .putLong(KEY_LAST_SUCCESS_AT, now)
            .apply {
                if (newest != null) putString(KEY_LAST_DATA_DATE, newest)
            }.apply()
        append(context, formatLine(now, trigger, result))
    }

    /** Records a failed attempt. The resume point is left untouched. */
    fun recordFailure(
        context: Context,
        trigger: String,
        detail: String,
        now: Long = System.currentTimeMillis(),
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_ATTEMPT_AT, now)
            .putString(KEY_LAST_ATTEMPT_RESULT, "failed: $detail")
            .apply()
        append(context, formatLine(now, trigger, "failed: $detail"))
    }

    /** Records that a run finished without anything to deliver. */
    fun recordIdle(
        context: Context,
        trigger: String,
        detail: String,
        now: Long = System.currentTimeMillis(),
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_ATTEMPT_AT, now)
            .putString(KEY_LAST_ATTEMPT_RESULT, "idle: $detail")
            .apply()
        append(context, formatLine(now, trigger, "idle: $detail"))
    }

    fun clear(context: Context) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ENTRIES)
            .apply()
    }

    private fun append(
        context: Context,
        line: String,
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val kept =
            (
                prefs
                    .getString(KEY_ENTRIES, "")
                    .orEmpty()
                    .lineSequence()
                    .filter { it.isNotBlank() }
                    .toList() + line
            ).takeLast(MAX_ENTRIES)
                .joinToString("\n")
        prefs.edit().putString(KEY_ENTRIES, kept).apply()
    }

    private fun formatLine(
        now: Long,
        trigger: String,
        result: String,
    ): String = "${timeFormatter.format(Instant.ofEpochMilli(now))} [$trigger] $result"
}
