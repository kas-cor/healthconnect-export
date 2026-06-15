package com.healthconnect.export.viewmodel

import android.app.Application
import android.content.Context
import com.healthconnect.export.R
import com.healthconnect.export.data.ExportConfig
import com.healthconnect.export.data.ExportFrequency
import com.healthconnect.export.worker.DailyExportWorker

/**
 * Manages schedule-related logic for health data export.
 *
 * Handles scheduling/cancelling periodic exports, the every-2-hours webhook,
 * and frequency selection. Communicates state changes back to the ViewModel
 * via the provided [onStateUpdate] callback.
 */
sealed class ScheduleStatus {
    object NotScheduled : ScheduleStatus()
    data class Scheduled(val nextRun: String) : ScheduleStatus()
    object Running : ScheduleStatus()
}

class ScheduleManager(
    private val application: Application,
    private val onStateUpdate: (ExportUiState.() -> ExportUiState) -> Unit
) {

    companion object {
        private const val PREFS_NAME = "healthconnect_export_prefs"
        private const val KEY_AUTO_SEND_WEBHOOK_EVERY_2_HOURS = "auto_send_webhook_every_2_hours"
    }

    // Helper to get localized strings from resources
    private fun str(id: Int): String = application.getString(id)
    private fun str(id: Int, vararg args: Any?): String =
        application.getString(id, *args)

    /**
     * Builds an [ExportConfig] from the current UI state snapshot.
     */
    private fun buildConfig(state: ExportUiState): ExportConfig = ExportConfig(
        enabledTypes = state.selectedTypes,
        frequency = state.frequency,
        autoSyncDrive = state.autoSyncDrive,
        webhookUrl = state.webhookUrl,
        webhookAuthToken = state.webhookAuthToken,
        autoSendWebhook = state.autoSendWebhook,
        autoSendWebhookEvery2Hours = state.autoSendWebhookEvery2Hours,
        selectedSourcePackage = state.selectedSourcePackage
    )

    /**
     * Updates the export frequency in the UI state.
     */
    fun setFrequency(freq: ExportFrequency) {
        onStateUpdate { copy(frequency = freq) }
    }

    /**
     * Schedules a periodic export based on the current configuration.
     * If frequency is MANUAL, cancels any existing schedule.
     */
    fun scheduleExport(state: ExportUiState) {
        val config = buildConfig(state)
        DailyExportWorker.schedule(application, config)
        onStateUpdate {
            copy(
                scheduleStatus = ScheduleStatus.Scheduled(str(R.string.next_run)),
                message = str(R.string.schedule_set, config.frequency.displayName)
            )
        }
    }

    /**
     * Cancels the current periodic export schedule.
     */
    fun cancelSchedule() {
        DailyExportWorker.cancel(application)
        onStateUpdate {
            copy(
                scheduleStatus = ScheduleStatus.NotScheduled,
                message = str(R.string.schedule_cancelled)
            )
        }
    }

    /**
     * Enables or disables the every-2-hours webhook worker.
     * Validates that a webhook URL is set before enabling.
     * Persists the setting to SharedPreferences.
     */
    fun setAutoSendWebhookEvery2Hours(state: ExportUiState, enabled: Boolean) {
        if (enabled && state.webhookUrl.isBlank()) {
            onStateUpdate { copy(message = str(R.string.enter_url_first_every_2h)) }
            return
        }
        onStateUpdate { copy(autoSendWebhookEvery2Hours = enabled) }
        saveAutoSendWebhookEvery2Hours(enabled)
        val config = buildConfig(state.copy(autoSendWebhookEvery2Hours = enabled))
        DailyExportWorker.scheduleEvery2HoursWebhook(application, config)
        if (enabled) {
            onStateUpdate { copy(message = str(R.string.every_2_hours_enabled)) }
        } else {
            onStateUpdate { copy(message = str(R.string.every_2_hours_disabled)) }
        }
    }

    private fun saveAutoSendWebhookEvery2Hours(enabled: Boolean) {
        application
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_SEND_WEBHOOK_EVERY_2_HOURS, enabled).apply()
    }
}
