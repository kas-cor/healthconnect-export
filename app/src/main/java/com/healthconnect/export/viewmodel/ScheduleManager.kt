package com.healthconnect.export.viewmodel

import android.app.Application
import com.healthconnect.export.R
import com.healthconnect.export.data.ExportConfig
import com.healthconnect.export.data.ExportFrequency
import com.healthconnect.export.util.ExportSettings
import com.healthconnect.export.worker.DailyExportWorker
import com.healthconnect.export.worker.DeliveryWatchdog

/**
 * Manages schedule-related logic for health data export.
 *
 * Handles scheduling/cancelling periodic exports, the every-2-hours webhook,
 * and frequency selection. Communicates state changes back to the ViewModel
 * via the provided [onStateUpdate] callback.
 */
sealed class ScheduleStatus {
    object NotScheduled : ScheduleStatus()

    data class Scheduled(
        val nextRun: String,
    ) : ScheduleStatus()

    object Running : ScheduleStatus()
}

class ScheduleManager(
    private val application: Application,
    private val onStateUpdate: (ExportUiState.() -> ExportUiState) -> Unit,
) {
    // Helper to get localized strings from resources
    private fun str(id: Int): String = application.getString(id)

    private fun str(
        id: Int,
        vararg args: Any?,
    ): String = application.getString(id, *args)

    /**
     * Builds an [ExportConfig] from the current UI state snapshot.
     */
    private fun buildConfig(state: ExportUiState): ExportConfig =
        ExportConfig(
            enabledTypes = state.selectedTypes,
            frequency = state.frequency,
            autoSyncDrive = state.autoSyncDrive,
            webhookUrl = state.webhookUrl,
            webhookAuthToken = state.webhookAuthToken,
            autoSendWebhook = state.autoSendWebhook,
            autoSendWebhookEvery2Hours = state.autoSendWebhookEvery2Hours,
            selectedSourcePackage = state.selectedSourcePackage,
            exportFormat = state.exportFormat,
            scheduleHour = state.scheduleHour,
        )

    /**
     * Updates the export frequency in the UI state.
     *
     * The choice is persisted, otherwise every launch silently fell back to
     * Daily — including the Manual option, which then started exporting again
     * by itself. Manual only cancels the periodic export: the every-2-hours
     * webhook has its own switch and must keep running.
     */
    fun setFrequency(freq: ExportFrequency) {
        onStateUpdate { copy(frequency = freq) }
        ExportSettings.saveFrequency(application, freq)
        if (freq == ExportFrequency.MANUAL) {
            DailyExportWorker.cancelDaily(application)
            DailyExportWorker.scheduleEvery2HoursWebhook(application, ExportSettings.loadConfig(application))
        }
    }

    /**
     * Schedules a periodic export based on the current configuration.
     * If frequency is MANUAL, cancels any existing schedule.
     *
     * @param showMessage Whether to surface a confirmation snackbar. Disabled
     *   when called at app start ([ExportViewModel] init), so launching the app
     *   does not pop up an unnecessary "Scheduled export set" message.
     */
    fun scheduleExport(
        state: ExportUiState,
        showMessage: Boolean = true,
    ) {
        val config = buildConfig(state)
        DailyExportWorker.schedule(application, config)
        // The watchdog alarm is an independent trigger: it survives Doze and OEM
        // background restrictions, where the periodic job alone can be starved.
        DeliveryWatchdog.armAlarm(application)
        onStateUpdate {
            copy(
                scheduleStatus =
                    if (config.frequency == ExportFrequency.MANUAL) {
                        ScheduleStatus.NotScheduled
                    } else {
                        ScheduleStatus.Scheduled(str(R.string.next_run))
                    },
                message =
                    if (showMessage && config.frequency != ExportFrequency.MANUAL) {
                        str(R.string.schedule_set, config.frequency.displayName)
                    } else {
                        null
                    },
            )
        }
    }

    /**
     * Cancels the current periodic export schedule.
     */
    fun cancelSchedule() {
        DailyExportWorker.cancel(application)
        DeliveryWatchdog.cancelAlarm(application)
        onStateUpdate {
            copy(
                scheduleStatus = ScheduleStatus.NotScheduled,
                message = str(R.string.schedule_cancelled),
            )
        }
    }

    /**
     * Cancels and re-creates the periodic export so that changes to the
     * schedule (e.g. the time of day) take effect immediately. Silent — no
     * confirmation snackbar.
     */
    fun rescheduleExport(state: ExportUiState) {
        DailyExportWorker.cancel(application)
        scheduleExport(state, showMessage = false)
    }
}
