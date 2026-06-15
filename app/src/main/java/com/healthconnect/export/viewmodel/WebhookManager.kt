package com.healthconnect.export.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.viewModelScope
import com.healthconnect.export.R
import com.healthconnect.export.data.DailyHealthRecord
import com.healthconnect.export.data.ExportConfig
import com.healthconnect.export.repository.HealthConnectRepository
import com.healthconnect.export.repository.WebhookRepository
import com.healthconnect.export.repository.WebhookResult
import com.healthconnect.export.worker.DailyExportWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Manages all webhook-related logic extracted from [ExportViewModel].
 *
 * Handles webhook URL/token persistence, validation, sending records,
 * testing webhook connectivity, and scheduling the every-2-hours webhook worker.
 */
class WebhookManager(
    private val application: Application,
    private val _uiState: MutableStateFlow<ExportUiState>,
    private val viewModelScope: kotlinx.coroutines.CoroutineScope,
    internal var healthRepo: HealthConnectRepository
) {

    private val webhookRepo = WebhookRepository()
    private var currentTestWebhookJob: Job? = null

    companion object {
        private const val PREFS_NAME = "healthconnect_export_prefs"
        private const val KEY_WEBHOOK_URL = "webhook_url"
        private const val KEY_WEBHOOK_TOKEN = "webhook_auth_token"
        private const val KEY_AUTO_SEND_WEBHOOK = "auto_send_webhook"
        private const val KEY_AUTO_SEND_WEBHOOK_EVERY_2_HOURS = "auto_send_webhook_every_2_hours"
    }

    // ------------------------------------------------------------------
    // Loading / persistence
    // ------------------------------------------------------------------

    /**
     * Loads webhook settings from SharedPreferences into UI state.
     * Called once during ViewModel initialization.
     */
    fun loadSettings() {
        val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val url = prefs.getString(KEY_WEBHOOK_URL, "") ?: ""
        val token = prefs.getString(KEY_WEBHOOK_TOKEN, "") ?: ""
        val autoSend = prefs.getBoolean(KEY_AUTO_SEND_WEBHOOK, false)
        val autoSend2h = prefs.getBoolean(KEY_AUTO_SEND_WEBHOOK_EVERY_2_HOURS, false)
        if (url.isNotBlank() || token.isNotBlank() || autoSend || autoSend2h) {
            val error = if (url.isNotBlank() && !webhookRepo.isValidWebhookUrl(url)) {
                application.getString(R.string.vm_invalid_url)
            } else null
            _uiState.update {
                it.copy(
                    webhookUrl = url,
                    webhookAuthToken = token,
                    autoSendWebhook = autoSend,
                    autoSendWebhookEvery2Hours = autoSend2h,
                    webhookUrlError = error
                )
            }
        }
    }

    private fun saveWebhookUrl(url: String) {
        application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_WEBHOOK_URL, url).apply()
    }

    private fun saveWebhookToken(token: String) {
        application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_WEBHOOK_TOKEN, token).apply()
    }

    private fun saveAutoSendWebhook(enabled: Boolean) {
        application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_SEND_WEBHOOK, enabled).apply()
    }

    private fun saveAutoSendWebhookEvery2Hours(enabled: Boolean) {
        application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_SEND_WEBHOOK_EVERY_2_HOURS, enabled).apply()
    }

    // ------------------------------------------------------------------
    // Public setters (called from ViewModel)
    // ------------------------------------------------------------------

    fun setWebhookUrl(url: String) {
        val error = if (url.isNotBlank() && !webhookRepo.isValidWebhookUrl(url)) {
            application.getString(R.string.vm_invalid_url)
        } else null
        _uiState.update { it.copy(webhookUrl = url, webhookUrlError = error) }
        saveWebhookUrl(url)
    }

    fun setWebhookAuthToken(token: String) {
        _uiState.update { it.copy(webhookAuthToken = token) }
        saveWebhookToken(token)
    }

    fun setAutoSendWebhook(enabled: Boolean) {
        _uiState.update { it.copy(autoSendWebhook = enabled) }
        saveAutoSendWebhook(enabled)
    }

    fun setAutoSendWebhookEvery2Hours(enabled: Boolean) {
        val state = _uiState.value
        if (enabled && state.webhookUrl.isBlank()) {
            _uiState.update { it.copy(message = application.getString(R.string.enter_url_first_every_2h)) }
            return
        }
        _uiState.update { it.copy(autoSendWebhookEvery2Hours = enabled) }
        saveAutoSendWebhookEvery2Hours(enabled)
        // Schedule or cancel the 2-hour worker
        val config = ExportConfig(
            enabledTypes = state.selectedTypes,
            frequency = state.frequency,
            autoSyncDrive = state.autoSyncDrive,
            webhookUrl = state.webhookUrl,
            webhookAuthToken = state.webhookAuthToken,
            autoSendWebhook = state.autoSendWebhook,
            autoSendWebhookEvery2Hours = enabled,
            selectedSourcePackage = state.selectedSourcePackage
        )
        DailyExportWorker.scheduleEvery2HoursWebhook(application, config)
        if (enabled) {
            _uiState.update {
                it.copy(message = application.getString(R.string.every_2_hours_enabled))
            }
        } else {
            _uiState.update {
                it.copy(message = application.getString(R.string.every_2_hours_disabled))
            }
        }
    }

    // ------------------------------------------------------------------
    // Sending records to webhook (called after export completes)
    // ------------------------------------------------------------------

    /**
     * Sends exported health records to the configured webhook URL.
     * Updates UI state with success/error messages.
     */
    fun sendToWebhook(url: String, authToken: String, records: List<DailyHealthRecord>) {
        viewModelScope.launch {
            _uiState.update { it.copy(exportProgress = application.getString(R.string.vm_sending_webhook)) }
            when (val result = webhookRepo.sendRecords(url, records, authToken)) {
                is WebhookResult.Success -> {
                    _uiState.update {
                        it.copy(
                            message = application.getString(R.string.vm_webhook_success, result.statusCode)
                        )
                    }
                }
                is WebhookResult.Error -> {
                    _uiState.update {
                        it.copy(
                            message = application.getString(R.string.vm_webhook_error, result.statusCode, result.message)
                        )
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Testing webhook connectivity
    // ------------------------------------------------------------------

    /**
     * Tests the webhook by reading today's health data and sending it.
     * Updates UI state throughout the process.
     */
    fun testWebhook() {
        val state = _uiState.value
        if (state.webhookUrl.isBlank() || state.webhookUrlError != null) {
            _uiState.update { it.copy(message = application.getString(R.string.enter_url_first)) }
            return
        }

        _uiState.update {
            it.copy(isTestingWebhook = true, message = application.getString(R.string.webhook_testing))
        }

        val job = viewModelScope.launch {
            try {
                val today = LocalDate.now()
                val records = healthRepo.readPeriodInBatch(
                    startDate = today,
                    endDate = today,
                    types = state.selectedTypes,
                    selectedSourcePackage = state.selectedSourcePackage
                )

                if (records.isEmpty()) {
                    _uiState.update {
                        it.copy(message = application.getString(R.string.vm_webhook_no_data))
                    }
                    return@launch
                }

                when (val result = webhookRepo.sendRecords(state.webhookUrl, records, state.webhookAuthToken)) {
                    is WebhookResult.Success -> {
                        _uiState.update {
                            it.copy(message = application.getString(R.string.vm_webhook_test_success, result.statusCode))
                        }
                    }
                    is WebhookResult.Error -> {
                        _uiState.update {
                            it.copy(message = application.getString(R.string.vm_webhook_test_error, result.statusCode, result.message))
                        }
                    }
                }
            } catch (e: CancellationException) {
                // Test was cancelled — do nothing, cancelTestWebhook() already reset state
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(message = application.getString(R.string.vm_webhook_test_error, 0, e.message ?: "Unknown error"))
                }
            } finally {
                _uiState.update { it.copy(isTestingWebhook = false) }
                currentTestWebhookJob = null
            }
        }
        currentTestWebhookJob = job
    }

    /**
     * Cancels an in-progress webhook test.
     */
    fun cancelTestWebhook() {
        currentTestWebhookJob?.cancel()
        currentTestWebhookJob = null
        _uiState.update {
            it.copy(
                isTestingWebhook = false,
                message = application.getString(R.string.vm_export_cancelled)
            )
        }
    }
}
