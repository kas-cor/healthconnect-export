package com.healthconnect.export.data

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate

/**
 * Persisted outcome of the last webhook send attempt.
 *
 * Diagnostics only: the Schedule screen uses it to show when data was last
 * delivered, and the app uses it to decide whether a catch-up run is needed.
 * A missing or unreadable store never breaks sending — every access is
 * best-effort (see [SendStats]).
 */
data class LastSend(
    val successTimestampMs: Long? = null,
    val successStatusCode: Int? = null,
    /** Latest record date included in the last successful payload. */
    val lastSentDate: LocalDate? = null,
    val failureTimestampMs: Long? = null,
    val failureStatusCode: Int? = null,
    val failureMessage: String? = null,
) {
    /** Whole hours since the last successful send, or null when nothing was ever sent. */
    fun hoursSinceSuccess(nowMs: Long = System.currentTimeMillis()): Long? =
        successTimestampMs?.let { (nowMs - it) / 3_600_000L }

    /**
     * True when a successful send happened and is at least [maxAgeHours] old.
     * A device that never sent anything is not considered stale — there is no
     * known-good point to resume from.
     */
    fun isStale(
        maxAgeHours: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = successTimestampMs != null && hoursSinceSuccess(nowMs)!! >= maxAgeHours
}

/**
 * SharedPreferences-backed store for [LastSend].
 *
 * Lives in the same prefs file as the rest of the app settings so that a
 * single backup/restore keeps the diagnostics intact.
 */
object SendStats {
    /** Prefs file used by the whole app (see ExportViewModel.PREFS_NAME). */
    private const val PREFS_NAME = "healthconnect_export_prefs"
    private const val KEY_SUCCESS_MS = "last_webhook_success_ms"
    private const val KEY_SUCCESS_CODE = "last_webhook_success_code"
    private const val KEY_SUCCESS_DATE = "last_webhook_sent_date"
    private const val KEY_FAILURE_MS = "last_webhook_failure_ms"
    private const val KEY_FAILURE_CODE = "last_webhook_failure_code"
    private const val KEY_FAILURE_MESSAGE = "last_webhook_failure_message"

    /**
     * Returns the prefs file, or null when the context cannot provide one
     * (e.g. a mocked context in unit tests). Diagnostics must never throw.
     */
    private fun prefs(context: Context): SharedPreferences? =
        runCatching { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }.getOrNull()

    /**
     * Records a successful delivery: timestamp, HTTP status and the newest
     * record date in the payload (used as the resume point for catch-up runs).
     */
    fun recordSuccess(
        context: Context,
        statusCode: Int,
        records: List<DailyHealthRecord>,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val store = prefs(context) ?: return
        val newestDate = records.mapNotNull { it.date.toLocalDateOrNull() }.maxOrNull()
        val editor =
            store
                .edit()
                .putLong(KEY_SUCCESS_MS, nowMs)
                .putInt(KEY_SUCCESS_CODE, statusCode)
                .remove(KEY_FAILURE_MESSAGE)
        if (newestDate != null) {
            editor.putString(KEY_SUCCESS_DATE, newestDate.toString())
        }
        editor.apply()
    }

    /**
     * Records a failed delivery. The previous success is intentionally kept
     * untouched so the UI can still show the last time data actually arrived.
     */
    fun recordFailure(
        context: Context,
        statusCode: Int,
        message: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val store = prefs(context) ?: return
        store
            .edit()
            .putLong(KEY_FAILURE_MS, nowMs)
            .putInt(KEY_FAILURE_CODE, statusCode)
            .putString(KEY_FAILURE_MESSAGE, message)
            .apply()
    }

    /** Reads the persisted last-send state; empty when nothing was recorded. */
    fun lastSend(context: Context): LastSend {
        val store = prefs(context) ?: return LastSend()
        return LastSend(
            successTimestampMs = store.getLong(KEY_SUCCESS_MS, 0L).takeIf { it > 0L },
            successStatusCode = store.getInt(KEY_SUCCESS_CODE, 0).takeIf { it > 0 },
            lastSentDate = store.getString(KEY_SUCCESS_DATE, null)?.toLocalDateOrNull(),
            failureTimestampMs = store.getLong(KEY_FAILURE_MS, 0L).takeIf { it > 0L },
            failureStatusCode = store.getInt(KEY_FAILURE_CODE, 0).takeIf { it > 0 },
            failureMessage = store.getString(KEY_FAILURE_MESSAGE, null)?.takeIf { it.isNotBlank() },
        )
    }

    private fun String.toLocalDateOrNull(): LocalDate? = runCatching { LocalDate.parse(this) }.getOrNull()
}
