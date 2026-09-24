package com.healthconnect.export.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists the last scheduled [ExportConfig] so background entry points that
 * do not have UI state (notably [com.healthconnect.export.worker.BootRescheduleReceiver]
 * after a reboot or an app update) can re-register the periodic work with the
 * user's real settings.
 *
 * Best-effort: a context without prefs (unit tests) simply stores nothing.
 */
object ScheduledConfigStore {
    private const val PREFS_NAME = "healthconnect_export_prefs"
    private const val KEY_SCHEDULED_CONFIG = "scheduled_config"

    private val json = Json { ignoreUnknownKeys = true }

    private fun prefs(context: Context): SharedPreferences? =
        runCatching { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }.getOrNull()

    fun save(
        context: Context,
        config: ExportConfig,
    ) {
        val store = prefs(context) ?: return
        val encoded = json.encodeToString(config)
        // App start re-schedules on every launch; writing an unchanged value
        // would be a pointless disk hit (and noise for anyone inspecting prefs).
        if (store.getString(KEY_SCHEDULED_CONFIG, null) == encoded) return
        store.edit()?.putString(KEY_SCHEDULED_CONFIG, encoded)?.apply()
    }

    /** Returns the stored config, or null after [clear]/a fresh install/corrupt JSON. */
    fun load(context: Context): ExportConfig? {
        val raw = prefs(context)?.getString(KEY_SCHEDULED_CONFIG, null) ?: return null
        return runCatching { json.decodeFromString<ExportConfig>(raw) }.getOrNull()
    }

    /** Drops the stored config — called when the schedule is cancelled. */
    fun clear(context: Context) {
        prefs(context)?.edit()?.remove(KEY_SCHEDULED_CONFIG)?.apply()
    }
}
