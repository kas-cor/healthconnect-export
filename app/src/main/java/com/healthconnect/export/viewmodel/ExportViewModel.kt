package com.healthconnect.export.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.healthconnect.export.R
import com.healthconnect.export.data.*
import com.healthconnect.export.repository.HealthConnectRepository
import com.healthconnect.export.usecase.ExportDataUseCase
import com.healthconnect.export.usecase.ExportStep
import com.healthconnect.export.util.LocaleManager
import com.healthconnect.export.repository.LocalExportRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
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
)

sealed class DriveStatus {
    object NotConnected : DriveStatus()
    object Connected : DriveStatus()
    object Syncing : DriveStatus()
    data class Synced(val filesCount: Int) : DriveStatus()
    data class Error(val error: String) : DriveStatus()
}

class ExportViewModel(application: Application) : AndroidViewModel(application) {

    /** Scope for export operations. Made internal for testability. */
    internal var exportScope: CoroutineScope = viewModelScope

    private var currentExportJob: Job? = null
    private var currentTestWebhookJob: Job? = null

    private val healthRepo = HealthConnectRepository(getApplication())
    private val localRepo = LocalExportRepository(getApplication())
    private val exportUseCase = ExportDataUseCase(healthRepo, localRepo)

    private val _uiState = MutableStateFlow(ExportUiState())
    val uiState = _uiState.asStateFlow()

    val driveManager = DriveManager(getApplication())
    val webhookManager = WebhookManager(
        application = getApplication(),
        _uiState = _uiState,
        viewModelScope = viewModelScope,
        healthRepo = healthRepo
    )
    val scheduleManager = ScheduleManager(getApplication()) { update ->
        _uiState.update(update)
    }

    /** Набор разрешений для запроса Health Connect */
    var pendingPermissions: Set<String>? = null
        private set

    companion object {
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
    }

    // Helper to get localized strings from resources
    private fun str(id: Int): String = getApplication<Application>().getString(id)
    private fun str(id: Int, vararg args: Any?): String =
        getApplication<Application>().getString(id, *args)

    init {
        loadSelectedTypes()
        loadThemePreference()
        loadDateRange()
        webhookManager.loadSettings()
        loadLocale()
        loadSourcePreference()
        driveManager.refreshDriveStatus()
        refreshLocalFiles()
        scheduleManager.scheduleExport(_uiState.value)
        fetchAvailableSources()
    }

    private fun loadSelectedTypes() {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getStringSet(KEY_SELECTED_TYPES, null)
        if (saved != null && saved.isNotEmpty()) {
            val types = saved.mapNotNull { name ->
                try { HealthDataType.valueOf(name) } catch (_: IllegalArgumentException) { null }
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
        val preset = presetName?.let {
            try { DateRangePreset.valueOf(it) } catch (_: IllegalArgumentException) { null }
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
            } catch (_: Exception) { /* ignore invalid dates */ }
        }
    }

    private fun saveDateRange(preset: DateRangePreset, start: LocalDate, end: LocalDate) {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
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
        val (start, end) = if (preset == DateRangePreset.NONE) {
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
        if (preset == null || preset == DateRangePreset.NONE) return
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
            .edit().putString(KEY_SOURCE_PACKAGE, sourcePackage).apply()
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
        val code = getApplication<Application>().let {
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
            .edit().putBoolean(KEY_AUTO_SYNC_DRIVE, enabled).apply()
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

    fun setDateRange(start: LocalDate, end: LocalDate) {
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
        // If already exporting — cancel instead
        if (_uiState.value.isLoading) {
            cancelExport()
            return
        }

        // If a sliding preset (7/30 days) is active, recompute the window from today
        // before exporting so the period moves as days pass.
        refreshSlidingDateRange()

        val job = exportScope.launch {
            try {
                val state = _uiState.value

                exportUseCase.execute(
                    context = getApplication(),
                    config = ExportConfig(
                        enabledTypes = state.selectedTypes,
                        frequency = state.frequency,
                        autoSyncDrive = state.autoSyncDrive,
                        selectedSourcePackage = state.selectedSourcePackage
                    ),
                    startDate = state.startDate,
                    endDate = state.endDate
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
                                            progressDate = parts[3]
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
                                            progressDate = parts[1] // type name
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
                                    message = str(R.string.vm_export_complete, step.files.size)
                                )
                            }
                            // Refresh the full file list from disk so the UI shows all exported files,
                            // not just the ones from this export run
                            refreshLocalFiles()
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
                                    message = step.message
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
                message = str(R.string.vm_export_cancelled)
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
                val missingNames = missing.mapNotNull { perm ->
                    val type = HealthDataType.entries
                        .sortedByDescending { it.name.length }
                        .firstOrNull { perm.contains(it.name) }
                    if (type != null) {
                        val resName = "data_type_${type.name}"
                        val ctx = getApplication<Application>()
                        val resId = ctx.resources.getIdentifier(resName, "string", ctx.packageName)
                        if (resId != 0) str(resId) else type.displayName
                    } else null
                }
                _uiState.update {
                    it.copy(
                        message = str(R.string.vm_permissions_missing, missingNames.joinToString(", "))
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
                message = driveState.message ?: it.message
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
        val config = ExportConfig(
            enabledTypes = _uiState.value.selectedTypes,
            frequency = _uiState.value.frequency,
            autoSyncDrive = _uiState.value.autoSyncDrive
        )
        val files = localRepo.listExportedFiles(config).map { it.second }
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
}
