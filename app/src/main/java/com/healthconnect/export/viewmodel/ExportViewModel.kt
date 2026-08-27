package com.healthconnect.export.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.healthconnect.export.BuildConfig
import com.healthconnect.export.R
import com.healthconnect.export.data.*
import com.healthconnect.export.repository.HealthConnectRepository
import com.healthconnect.export.repository.LocalExportRepository
import com.healthconnect.export.usecase.ExportDataUseCase
import com.healthconnect.export.usecase.ExportStep
import com.healthconnect.export.util.LocaleManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

data class ExportUiState(
    val isLoading: Boolean = false,
    val exportProgress: String = "",
    val exportedFiles: List<File> = emptyList(),
    val driveStatus: DriveStatus = DriveStatus.NotConnected,
    val scheduleStatus: ScheduleStatus = ScheduleStatus.NotScheduled,
    val selectedTypes: Set<HealthDataType> = HealthDataType.entries.toSet(),
    val dateRangePreset: DateRangePreset = DateRangePreset.NONE,
    val startDate: LocalDate = LocalDate.now().minusDays(7),
    val endDate: LocalDate = LocalDate.now(),
    val frequency: ExportFrequency = ExportFrequency.DAILY,
    val autoSyncDrive: Boolean = true,
    val webhookUrl: String = "",
    val webhookAuthToken: String = "",
    val autoSendWebhook: Boolean = false,
    val autoSendWebhookEvery2Hours: Boolean = false,
    val webhookUrlError: String? = null,
    val message: String? = null,
    val isDarkTheme: Boolean? = null,
    val locale: String? = null,
    val availableSources: List<String> = emptyList(),
    val selectedSourcePackage: String? = null,
    val sourcesLoading: Boolean = false,
    val exportSummary: ExportSummary? = null,
    val progressCurrent: Int = 0,
    val progressTotal: Int = 0,
    val progressDate: String = "",
    val progressPhase: String = "",
    val isTestingWebhook: Boolean = false,
    val updateCheckState: UpdateCheckState = UpdateCheckState.Idle,
    val exportFormat: ExportFormat = ExportFormat.JSON,
    val scheduleHour: Int? = null,
    val retentionDays: Int? = null,
)

sealed class DriveStatus {
    object NotConnected : DriveStatus()

    object Connected : DriveStatus()

    object Syncing : DriveStatus()

    data class Synced(
        val filesCount: Int,
    ) : DriveStatus()

    data class Error(
        val error: String,
    ) : DriveStatus()
}

class ExportViewModel(
    application: Application,
) : AndroidViewModel(application) {
    /** Scope for export operations. Made internal for testability. */
    internal var exportScope: CoroutineScope = viewModelScope

    private var currentExportJob: Job? = null
    private var currentTestWebhookJob: Job? = null

    private val healthRepo = HealthConnectRepository(getApplication())
    private val localRepo = LocalExportRepository(getApplication())
    private val exportUseCase = ExportDataUseCase(healthRepo, localRepo)

    private val releaseJson = Json { ignoreUnknownKeys = true }

    private val _uiState = MutableStateFlow(ExportUiState())
    val uiState = _uiState.asStateFlow()

    val driveManager = DriveManager(getApplication())
    val webhookManager =
        WebhookManager(
            application = getApplication(),
            _uiState = _uiState,
            viewModelScope = viewModelScope,
            healthRepo = healthRepo,
        )
    val scheduleManager =
        ScheduleManager(getApplication()) { update ->
            _uiState.update(update)
        }

    /** Набор разрешений для запроса Health Connect */
    var pendingPermissions: Set<String>? = null
        private set

    companion object {
        // Cap response body read to 1 MB to avoid OOM on a misbehaving/gigantic response
        private const val MAX_RESPONSE_BYTES = 1_048_576
        private const val PREFS_NAME = "healthconnect_export_prefs"
        private const val KEY_SELECTED_TYPES = "selected_types"
        private const val KEY_DARK_THEME = "dark_theme"
        private const val KEY_START_DATE = "start_date"
        private const val KEY_END_DATE = "end_date"
        private const val KEY_DATE_RANGE_PRESET = "date_range_preset"
        private const val KEY_WEBHOOK_URL = "webhook_url"
        private const val KEY_WEBHOOK_TOKEN = "webhook_auth_token"
        private const val KEY_AUTO_SEND_WEBHOOK = "auto_send_webhook"
        private const val KEY_AUTO_SEND_WEBHOOK_EVERY_2_HOURS = "auto_send_webhook_every_2_hours"
        private const val KEY_AUTO_SYNC_DRIVE = "auto_sync_drive"
        private const val KEY_LOCALE = "app_locale"
        private const val KEY_SOURCE_PACKAGE = "selected_source_package"
        private const val KEY_EXPORT_FORMAT = "export_format"
        private const val KEY_SCHEDULE_HOUR = "schedule_hour"
        private const val KEY_RETENTION_DAYS = "retention_days"
    }

    // Helper to get localized strings from resources
    private fun str(id: Int): String = getApplication<Application>().getString(id)

    private fun str(
        id: Int,
        vararg args: Any?,
    ): String = getApplication<Application>().getString(id, *args)

    init {
        loadSelectedTypes()
        loadThemePreference()
        loadDateRange()
        webhookManager.loadSettings()
        loadLocale()
        loadSourcePreference()
        loadExportFormat()
        loadScheduleHour()
        loadRetentionDays()
        driveManager.refreshDriveStatus()
        // Keep uiState.driveStatus in sync with DriveManager: the status is
        // updated asynchronously (e.g. after a silent sign-in at startup or
        // when the Drive file listing finishes), so it is collected here.
        viewModelScope.launch {
            driveManager.driveState.collect { driveState ->
                _uiState.update { it.copy(driveStatus = driveState.status) }
            }
        }
        refreshLocalFiles()
        // Apply the retention policy on start (if enabled) so old files are
        // cleaned up even if no export happens for a while.
        applyRetentionCleanup()
        // Re-schedule the periodic export without popping a confirmation
        // snackbar at every app start.
        scheduleManager.scheduleExport(_uiState.value, showMessage = false)
        fetchAvailableSources()
    }

    private fun loadSelectedTypes() {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getStringSet(KEY_SELECTED_TYPES, null)
        if (saved != null && saved.isNotEmpty()) {
            val types =
                saved
                    .mapNotNull { name ->
                        try {
                            HealthDataType.valueOf(name)
                        } catch (_: IllegalArgumentException) {
                            null
                        }
                    }.toSet()
            if (types.isNotEmpty()) {
                _uiState.update { it.copy(selectedTypes = types) }
            }
        }
    }

    private fun saveSelectedTypes(types: Set<HealthDataType>) {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_SELECTED_TYPES, types.map { it.name }.toSet()).apply()
    }

    private fun loadThemePreference() {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_DARK_THEME)) {
            _uiState.update { it.copy(isDarkTheme = prefs.getBoolean(KEY_DARK_THEME, false)) }
        }
    }

    private fun saveThemePreference(darkTheme: Boolean?) {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (darkTheme != null) {
            prefs.edit().putBoolean(KEY_DARK_THEME, darkTheme).apply()
        } else {
            prefs.edit().remove(KEY_DARK_THEME).apply()
        }
    }

    private fun loadDateRange() {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Load the preset selection. If a sliding preset (7/30 days) is active,
        // recompute the window from the current date instead of restoring frozen dates.
        val presetName = prefs.getString(KEY_DATE_RANGE_PRESET, null)
        val preset =
            presetName?.let {
                try {
                    DateRangePreset.valueOf(it)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }

        if (preset != null && preset != DateRangePreset.NONE) {
            val (start, end) = preset.calcRange(LocalDate.now())
            _uiState.update {
                it.copy(dateRangePreset = preset, startDate = start, endDate = end)
            }
            return
        }

        val startStr = prefs.getString(KEY_START_DATE, null)
        val endStr = prefs.getString(KEY_END_DATE, null)
        if (startStr != null && endStr != null) {
            try {
                val start = LocalDate.parse(startStr)
                val end = LocalDate.parse(endStr)
                _uiState.update {
                    it.copy(dateRangePreset = DateRangePreset.NONE, startDate = start, endDate = end)
                }
            } catch (_: Exception) {
                // ignore invalid dates
            }
        }
    }

    private fun saveDateRange(
        preset: DateRangePreset,
        start: LocalDate,
        end: LocalDate,
    ) {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs
            .edit()
            .putString(KEY_DATE_RANGE_PRESET, preset.name)
            .putString(KEY_START_DATE, start.toString())
            .putString(KEY_END_DATE, end.toString())
            .apply()
    }

    /**
     * Applies a preset (or custom) date range to the UI state and persists it.
     *
     * When a sliding preset is chosen, dates are recomputed from the current date
     * immediately and on every subsequent launch/export.
     */
    fun setDateRange(preset: DateRangePreset) {
        val (start, end) =
            if (preset == DateRangePreset.NONE) {
                _uiState.value.startDate to _uiState.value.endDate
            } else {
                preset.calcRange(LocalDate.now())
            }
        _uiState.update {
            it.copy(dateRangePreset = preset, startDate = start, endDate = end)
        }
        saveDateRange(preset, start, end)
    }

    /**
     * Before an export, if a sliding preset is active, recompute the window from the
     * current date so the period keeps moving as days pass (e.g. a 7-day preset exports
     * the last 7 days every time, not the frozen dates picked weeks ago).
     */
    fun refreshSlidingDateRange() {
        val preset = _uiState.value.dateRangePreset
        if (preset == DateRangePreset.NONE) return
        val (start, end) = preset.calcRange(LocalDate.now())
        if (start != _uiState.value.startDate || end != _uiState.value.endDate) {
            _uiState.update { it.copy(startDate = start, endDate = end) }
            saveDateRange(preset, start, end)
        }
    }

    private fun loadSourcePreference() {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_SOURCE_PACKAGE, null)
        if (saved != null) {
            _uiState.update { it.copy(selectedSourcePackage = saved) }
        }
    }

    private fun saveSourcePreference(sourcePackage: String?) {
        getApplication<Application>()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SOURCE_PACKAGE, sourcePackage)
            .apply()
    }

    fun setSourcePackage(sourcePackage: String?) {
        _uiState.update { it.copy(selectedSourcePackage = sourcePackage) }
        saveSourcePreference(sourcePackage)
    }

    fun fetchAvailableSources() {
        viewModelScope.launch {
            _uiState.update { it.copy(sourcesLoading = true) }
            try {
                val sources = healthRepo.getAvailableSources()
                _uiState.update {
                    it.copy(availableSources = sources.sorted(), sourcesLoading = false)
                }
            } catch (e: Exception) {
                Log.e("ExportViewModel", "Failed to fetch sources", e)
                _uiState.update { it.copy(sourcesLoading = false) }
            }
        }
    }

    private fun loadLocale() {
        val code =
            getApplication<Application>().let {
                LocaleManager.getSavedLocale(it)
            }
        _uiState.update { it.copy(locale = code) }
    }

    fun setLocale(code: String?) {
        _uiState.update { it.copy(locale = code) }
        getApplication<Application>().let {
            LocaleManager.saveLocale(it, code)
        }
    }

    private fun saveAutoSyncDrive(enabled: Boolean) {
        getApplication<Application>()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_SYNC_DRIVE, enabled)
            .apply()
    }

    fun setDarkTheme(darkTheme: Boolean?) {
        _uiState.update { it.copy(isDarkTheme = darkTheme) }
        saveThemePreference(darkTheme)
    }

    fun handleSignInResult(result: ActivityResult) {
        driveManager.handleSignInResult(result)
        // Sync DriveManager's state back to our UI state
        val driveState = driveManager.driveState.value
        _uiState.update {
            it.copy(driveStatus = driveState.status, message = driveState.message)
        }
    }

    fun signOut() {
        driveManager.signOut()
        val driveState = driveManager.driveState.value
        _uiState.update {
            it.copy(driveStatus = driveState.status, message = driveState.message)
        }
    }

    fun selectTypes(types: Set<HealthDataType>) {
        _uiState.update { it.copy(selectedTypes = types) }
        saveSelectedTypes(types)
    }

    fun setDateRange(
        start: LocalDate,
        end: LocalDate,
    ) {
        _uiState.update {
            it.copy(dateRangePreset = DateRangePreset.NONE, startDate = start, endDate = end)
        }
        saveDateRange(DateRangePreset.NONE, start, end)
    }

    fun setFrequency(freq: ExportFrequency) {
        scheduleManager.setFrequency(freq)
    }

    fun setAutoSyncDrive(enabled: Boolean) {
        _uiState.update { it.copy(autoSyncDrive = enabled) }
        saveAutoSyncDrive(enabled)
    }

    // ── Export format ────────────────────────────────────────────────────────

    private fun loadExportFormat() {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_EXPORT_FORMAT, null)
        val format =
            name?.let {
                try {
                    ExportFormat.valueOf(it)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
        if (format != null) {
            _uiState.update { it.copy(exportFormat = format) }
        }
    }

    private fun saveExportFormat(format: ExportFormat) {
        getApplication<Application>()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_EXPORT_FORMAT, format.name)
            .apply()
    }

    fun setExportFormat(format: ExportFormat) {
        _uiState.update { it.copy(exportFormat = format) }
        saveExportFormat(format)
    }

    // ── Schedule time of day ─────────────────────────────────────────────────

    private fun loadScheduleHour() {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_SCHEDULE_HOUR)) {
            _uiState.update { it.copy(scheduleHour = prefs.getInt(KEY_SCHEDULE_HOUR, -1).takeIf { h -> h in 0..23 }) }
        }
    }

    private fun saveScheduleHour(hour: Int?) {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (hour != null) {
            prefs.edit().putInt(KEY_SCHEDULE_HOUR, hour).apply()
        } else {
            prefs.edit().remove(KEY_SCHEDULE_HOUR).apply()
        }
    }

    fun setScheduleHour(hour: Int?) {
        _uiState.update { it.copy(scheduleHour = hour) }
        saveScheduleHour(hour)
        // Apply the new time immediately if a schedule is already active
        if (_uiState.value.frequency != ExportFrequency.MANUAL) {
            scheduleManager.rescheduleExport(_uiState.value)
        }
    }

    // ── Retention (auto-cleanup of old files) ───────────────────────────────

    private fun loadRetentionDays() {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_RETENTION_DAYS)) {
            _uiState.update { it.copy(retentionDays = prefs.getInt(KEY_RETENTION_DAYS, 0).takeIf { d -> d > 0 }) }
        }
    }

    private fun saveRetentionDays(days: Int?) {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (days != null) {
            prefs.edit().putInt(KEY_RETENTION_DAYS, days).apply()
        } else {
            prefs.edit().remove(KEY_RETENTION_DAYS).apply()
        }
    }

    fun setRetentionDays(days: Int?) {
        _uiState.update { it.copy(retentionDays = days) }
        saveRetentionDays(days)
        applyRetentionCleanup()
    }

    /**
     * Deletes exported files older than the retention period (if enabled)
     * and refreshes the file list.
     */
    private fun applyRetentionCleanup() {
        val days = _uiState.value.retentionDays ?: return
        localRepo.cleanupOldExports(days, currentExportConfig())
        refreshLocalFiles()
    }

    // ── File actions (share / delete) ───────────────────────────────────────

    /**
     * Shares an exported file via the Android share sheet (FileProvider URI).
     */
    fun shareExportFile(file: File) {
        try {
            val app = getApplication<Application>()
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = if (file.extension.equals("csv", ignoreCase = true)) "text/csv" else "application/json"
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val chooser = Intent.createChooser(intent, str(R.string.share_file))
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(chooser)
        } catch (e: Exception) {
            _uiState.update { it.copy(message = str(R.string.share_file_error)) }
        }
    }

    /**
     * Deletes an exported file (both JSON and CSV variants for that day).
     */
    fun deleteExportFile(file: File) {
        val dateStr =
            file.name
                .removePrefix("health_")
                .removeSuffix(".json")
                .removeSuffix(".csv")
        val date =
            try {
                LocalDate.parse(dateStr)
            } catch (e: Exception) {
                null
            }
        if (date != null) {
            localRepo.deleteExport(date, currentExportConfig())
            refreshLocalFiles()
            _uiState.update { it.copy(message = str(R.string.file_deleted, file.name)) }
        }
    }

    /**
     * Builds an [ExportConfig] snapshot from the current UI state.
     */
    private fun currentExportConfig(): ExportConfig {
        val s = _uiState.value
        return ExportConfig(
            enabledTypes = s.selectedTypes,
            frequency = s.frequency,
            autoSyncDrive = s.autoSyncDrive,
            webhookUrl = s.webhookUrl,
            webhookAuthToken = s.webhookAuthToken,
            autoSendWebhook = s.autoSendWebhook,
            autoSendWebhookEvery2Hours = s.autoSendWebhookEvery2Hours,
            selectedSourcePackage = s.selectedSourcePackage,
            exportFormat = s.exportFormat,
            scheduleHour = s.scheduleHour,
        )
    }

    fun setWebhookUrl(url: String) {
        webhookManager.setWebhookUrl(url)
    }

    fun setWebhookAuthToken(token: String) {
        webhookManager.setWebhookAuthToken(token)
    }

    fun setAutoSendWebhook(enabled: Boolean) {
        webhookManager.setAutoSendWebhook(enabled)
    }

    fun setAutoSendWebhookEvery2Hours(enabled: Boolean) {
        webhookManager.setAutoSendWebhookEvery2Hours(enabled)
    }

    fun scheduleExport() {
        scheduleManager.scheduleExport(_uiState.value)
    }

    fun cancelSchedule() {
        scheduleManager.cancelSchedule()
    }

    fun exportNow() {
        Log.d("ExportViewModel", "exportNow() called")
        val currentState = _uiState.value
        if (currentState.selectedTypes.isEmpty()) {
            _uiState.update { it.copy(message = str(R.string.vm_no_data_types)) }
            return
        }
        if (currentState.startDate.isAfter(currentState.endDate)) {
            _uiState.update { it.copy(message = str(R.string.vm_invalid_date_range)) }
            return
        }
        // If already exporting — cancel instead
        if (_uiState.value.isLoading) {
            cancelExport()
            return
        }

        // If a sliding preset (7/30 days) is active, recompute the window from today
        // before exporting so the period moves as days pass.
        refreshSlidingDateRange()

        val job =
            exportScope.launch {
                try {
                    val state = _uiState.value

                    exportUseCase
                        .execute(
                            context = getApplication(),
                            config = currentExportConfig(),
                            startDate = state.startDate,
                            endDate = state.endDate,
                        ).collect { step ->
                            when (step) {
                                is ExportStep.CheckingPermissions -> {
                                    _uiState.update {
                                        it.copy(isLoading = true, exportProgress = str(R.string.vm_check_permissions))
                                    }
                                }
                                is ExportStep.HealthNotAvailable -> {
                                    _uiState.update {
                                        it.copy(isLoading = false, message = str(R.string.vm_health_not_available))
                                    }
                                }
                                is ExportStep.HealthNotInstalled -> {
                                    _uiState.update {
                                        it.copy(isLoading = false, message = str(R.string.vm_health_not_installed))
                                    }
                                }
                                is ExportStep.PermissionsRequired -> {
                                    pendingPermissions = step.permissions
                                    _uiState.update {
                                        it.copy(isLoading = false, message = str(R.string.vm_permissions_required))
                                    }
                                }
                                is ExportStep.Progress -> {
                                    val parts = step.message.split(":")
                                    when {
                                        // Save progress: "save:current:total:date"
                                        parts.size == 4 && parts[0] == "save" -> {
                                            _uiState.update {
                                                it.copy(
                                                    exportProgress = step.message,
                                                    progressPhase = "save",
                                                    progressCurrent = parts[1].toIntOrNull() ?: 0,
                                                    progressTotal = parts[2].toIntOrNull() ?: 0,
                                                    progressDate = parts[3],
                                                )
                                            }
                                        }
                                        // Read page progress: "read:typeName:pageNumber"
                                        parts.size == 3 && parts[0] == "read" -> {
                                            _uiState.update {
                                                it.copy(
                                                    exportProgress = step.message,
                                                    progressPhase = "read",
                                                    progressCurrent = parts[2].toIntOrNull() ?: 0,
                                                    progressTotal = 0, // unknown total pages
                                                    progressDate = parts[1], // type name
                                                )
                                            }
                                        }
                                        else -> {
                                            _uiState.update { it.copy(exportProgress = step.message) }
                                        }
                                    }
                                }
                                is ExportStep.Complete -> {
                                    _uiState.update {
                                        it.copy(
                                            isLoading = false,
                                            exportProgress = str(R.string.vm_saved_files, step.files.size),
                                            exportedFiles = step.files,
                                            exportSummary = step.summary,
                                            message = str(R.string.vm_export_complete, step.files.size),
                                        )
                                    }
                                    // Refresh the full file list from disk so the UI shows all exported files,
                                    // not just the ones from this export run
                                    refreshLocalFiles()
                                    // Apply the retention policy after a successful export
                                    applyRetentionCleanup()
                                    // Post-export: auto-sync to Drive
                                    if (state.autoSyncDrive && driveManager.driveRepo.isSignedIn()) {
                                        syncToDrive(step.files)
                                    }
                                    // Post-export: send to webhook
                                    if (state.autoSendWebhook && state.webhookUrl.isNotBlank()) {
                                        webhookManager.sendToWebhook(state.webhookUrl, state.webhookAuthToken, step.records)
                                    }
                                }
                                is ExportStep.Error -> {
                                    _uiState.update {
                                        it.copy(
                                            isLoading = false,
                                            message = step.message,
                                        )
                                    }
                                }
                            }
                        }
                } catch (e: CancellationException) {
                    // Export was cancelled — do nothing, cancelExport() already reset state
                } finally {
                    currentExportJob = null
                }
            }
        currentExportJob = job
    }

    fun cancelExportNow() {
        cancelExport()
    }

    private fun cancelExport() {
        currentExportJob?.cancel()
        currentExportJob = null
        _uiState.update {
            it.copy(
                isLoading = false,
                exportProgress = "",
                progressPhase = "",
                progressCurrent = 0,
                progressTotal = 0,
                progressDate = "",
                message = str(R.string.vm_export_cancelled),
            )
        }
    }

    fun onPermissionsResult(grantedPermissions: Set<String>) {
        pendingPermissions = null
        viewModelScope.launch {
            val state = _uiState.value
            Log.d("ExportViewModel", "onPermissionsResult: launcher returned ${grantedPermissions.size}, checking via API...")
            val actualGranted = healthRepo.getGrantedPermissions()
            Log.d("ExportViewModel", "onPermissionsResult: API returned ${actualGranted.size} granted permissions")
            Log.d("ExportViewModel", "All granted permissions: $actualGranted")
            val required = healthRepo.getPermissionsForTypes(state.selectedTypes)
            if (actualGranted.containsAll(required)) {
                _uiState.update { it.copy(message = str(R.string.vm_permissions_granted)) }
                exportNow()
            } else {
                val missing = required - actualGranted
                val missingNames =
                    missing.mapNotNull { perm ->
                        val type =
                            HealthDataType.entries
                                .sortedByDescending { it.name.length }
                                .firstOrNull { perm.contains(it.name) }
                        if (type != null) {
                            val resName = "data_type_${type.name}"
                            val ctx = getApplication<Application>()
                            val resId = ctx.resources.getIdentifier(resName, "string", ctx.packageName)
                            if (resId != 0) str(resId) else type.displayName
                        } else {
                            null
                        }
                    }
                _uiState.update {
                    it.copy(
                        message = str(R.string.vm_permissions_missing, missingNames.joinToString(", ")),
                    )
                }
            }
        }
    }

    fun syncToDrive(files: List<File> = _uiState.value.exportedFiles) {
        driveManager.syncToDrive(files)
        val driveState = driveManager.driveState.value
        _uiState.update {
            it.copy(
                driveStatus = driveState.status,
                // Only overwrite message if Drive has one to show;
                // preserves messages set by the caller (e.g. export complete)
                message = driveState.message ?: it.message,
            )
        }
    }

    fun refreshDriveStatus() {
        driveManager.refreshDriveStatus()
        val driveState = driveManager.driveState.value
        _uiState.update {
            it.copy(driveStatus = driveState.status)
        }
    }

    fun refreshLocalFiles() {
        val files = localRepo.listExportedFiles(currentExportConfig()).map { it.second }
        _uiState.update { it.copy(exportedFiles = files) }
    }

    fun testWebhook() {
        webhookManager.testWebhook()
    }

    fun cancelTestWebhook() {
        webhookManager.cancelTestWebhook()
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun dismissSummary() {
        _uiState.update { it.copy(exportSummary = null) }
    }

    // =============================================
    // Update check (GitHub releases)
    // =============================================

    /**
     * Checks GitHub releases for a newer app version and updates the UI state.
     * When a newer version is found, also fetches its release notes.
     * The URLs can be overridden for testing.
     */
    fun checkForUpdates(
        releasesUrl: String? = null,
        apiReleasesUrl: String? = null,
    ) {
        if (_uiState.value.updateCheckState is UpdateCheckState.Checking) return
        _uiState.update { it.copy(updateCheckState = UpdateCheckState.Checking) }

        val url = releasesUrl ?: getApplication<Application>().getString(R.string.releases_url)
        val apiUrl = apiReleasesUrl ?: getApplication<Application>().getString(R.string.api_releases_url)
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    val version = fetchLatestRelease(url)
                    if (version is UpdateCheckState.Available) {
                        version.copy(releaseNotes = fetchLatestReleaseNotes(apiUrl))
                    } else {
                        version
                    }
                }
            _uiState.update { it.copy(updateCheckState = result) }
        }
    }

    fun resetUpdateCheck() {
        _uiState.update { it.copy(updateCheckState = UpdateCheckState.Idle) }
    }

    /**
     * Performs the actual GitHub release check via a HEAD request.
     * GitHub redirects /releases/latest to /releases/tag/vX.Y.Z — the tag
     * version is extracted from the redirect Location header and compared
     * with the installed version.
     */
    internal fun fetchLatestRelease(releasesUrl: String): UpdateCheckState {
        return try {
            val connection = URL(releasesUrl).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "HEAD"
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                // Handle the redirect manually so we can read the resolved release URL
                connection.instanceFollowRedirects = false
                connection.connect()

                val responseCode = connection.responseCode
                val location = connection.getHeaderField("Location")

                if (responseCode !in 300..399 || location.isNullOrBlank()) {
                    return UpdateCheckState.Error(str(R.string.update_check_error))
                }

                // Location can be relative (/kas-cor/.../tag/v1.7) or absolute (https://github.com/...)
                val resolvedUrl =
                    if (location.startsWith("http")) {
                        location
                    } else {
                        "https://github.com$location"
                    }
                // GitHub redirects /releases/latest to a /releases/tag/ URL. Anything else
                // (e.g. /releases when the repository has no releases) is not a version
                // we can compare against.
                if (!resolvedUrl.contains("/releases/tag/")) {
                    return UpdateCheckState.Error(str(R.string.update_check_error))
                }
                val tagVersion = resolvedUrl.substringAfterLast("/").removePrefix("v")
                if (tagVersion.isEmpty()) {
                    return UpdateCheckState.Error(str(R.string.update_check_error))
                }

                if (isVersionNewer(tagVersion, BuildConfig.VERSION_NAME)) {
                    UpdateCheckState.Available(
                        latestVersion = tagVersion,
                        downloadUrl = resolvedUrl,
                    )
                } else {
                    UpdateCheckState.UpToDate
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            UpdateCheckState.Error(str(R.string.update_check_error))
        }
    }

    /**
     * Compares two dotted version strings (e.g. "1.7.2" vs "1.6").
     * Returns true if [latest] is strictly newer than [current].
     */
    internal fun isVersionNewer(
        latest: String,
        current: String,
    ): Boolean {
        val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l != c) return l > c
        }
        return false
    }

    /**
     * Fetches the release notes (body) of the latest GitHub release via the
     * GitHub API. Returns null when the notes can't be retrieved.
     *
     * The API's /releases/latest matches the version resolved via the redirect
     * (both return the newest published release for this repo).
     */
    internal fun fetchLatestReleaseNotes(apiReleasesUrl: String): String? {
        return try {
            val connection = URL(apiReleasesUrl).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "HealthConnectExport")
                connection.connect()

                if (connection.responseCode !in 200..299) {
                    return null
                }
                val body = connection.inputStream.bufferedReader().use { readBounded(it) }
                val release = releaseJson.decodeFromString<GitHubRelease>(body)
                release.body.takeIf { it.isNotBlank() }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Caps response body reads to 1 MB to avoid OOM on a misbehaving/gigantic
     * response (mirrors the defensive read in WebhookRepository).
     */
    private fun readBounded(
        reader: java.io.Reader,
        maxBytes: Int = MAX_RESPONSE_BYTES,
    ): String {
        val buffer = CharArray(8192)
        val out = StringBuilder()
        var total = 0
        while (total < maxBytes) {
            val read = reader.read(buffer)
            if (read == -1) break
            if (read == 0) continue
            val room = maxBytes - total
            out.append(buffer, 0, minOf(read, room))
            total += minOf(read, room)
            if (total >= maxBytes) break
        }
        return out.toString()
    }
}

/**
 * Subset of the GitHub API release payload used to read the release notes.
 */
@Serializable
private data class GitHubRelease(
    val body: String = "",
)

sealed class UpdateCheckState {
    data object Idle : UpdateCheckState()

    data object Checking : UpdateCheckState()

    data object UpToDate : UpdateCheckState()

    data class Available(
        val latestVersion: String,
        val downloadUrl: String,
        val releaseNotes: String? = null,
    ) : UpdateCheckState()

    data class Error(
        val message: String,
    ) : UpdateCheckState()
}
