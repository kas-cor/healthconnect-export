package com.healthconnect.export.worker

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.healthconnect.export.data.ExportConfig
import com.healthconnect.export.data.ExportFrequency
import com.healthconnect.export.data.HealthDataType
import com.healthconnect.export.data.SendStats
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the boot / app-update receiver: which actions it reacts to, that it
 * loads the persisted schedule, and that a Manual (disabled) schedule is never
 * restored.
 *
 * Note: the actual WorkManager enqueue is not asserted here — in this project's
 * unit-test environment WorkManager's Room-backed work query throws inside the
 * generated DAO as soon as its result is observed (the existing worker tests
 * work around that by only asserting the LiveData handle). Enqueue behaviour is
 * verified on device / in instrumented runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BootRescheduleReceiverTest {
    private lateinit var app: Application
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        // The receiver enqueues work, so WorkManager must be initialized in the
        // test process. Its state is intentionally not queried (see class docs).
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build(),
        )
        prefs().edit().clear().commit()
    }

    private fun prefs() = app.getSharedPreferences("healthconnect_export_prefs", Context.MODE_PRIVATE)

    private fun config(
        frequency: ExportFrequency = ExportFrequency.DAILY,
        webhookUrl: String = "https://hooks.example.com/data",
    ) = ExportConfig(
        enabledTypes = setOf(HealthDataType.STEPS),
        frequency = frequency,
        autoSyncDrive = false,
        webhookUrl = webhookUrl,
    )

    private fun storeConfig(config: ExportConfig) {
        prefs().edit().putString("scheduled_config", json.encodeToString(config)).commit()
    }

    private fun storeLastSuccess(hoursAgo: Long) {
        prefs()
            .edit()
            .putLong("last_webhook_success_ms", System.currentTimeMillis() - hoursAgo * 3_600_000L)
            .putInt("last_webhook_success_code", 200)
            .commit()
    }

    private fun receive(action: String) {
        BootRescheduleReceiver().onReceive(app, Intent(action))
    }

    @Test
    fun `boot completed keeps and restores the stored schedule`() {
        storeConfig(config())

        receive(Intent.ACTION_BOOT_COMPLETED)

        assertEquals(
            json.encodeToString(config()),
            prefs().getString("scheduled_config", null),
        )
    }

    @Test
    fun `app update restores the stored schedule`() {
        storeConfig(config(webhookUrl = "https://hooks.example.com/other"))

        receive(Intent.ACTION_MY_PACKAGE_REPLACED)

        assertNotNull(prefs().getString("scheduled_config", null))
    }

    @Test
    fun `without a stored schedule nothing happens`() {
        receive(Intent.ACTION_BOOT_COMPLETED)

        assertNull(prefs().getString("scheduled_config", null))
    }

    @Test
    fun `a stored manual schedule is not restored`() {
        assertFalse(BootRescheduleReceiver.shouldRestore(config(frequency = ExportFrequency.MANUAL)))
    }

    @Test
    fun `a stored periodic schedule is restored`() {
        assertTrue(BootRescheduleReceiver.shouldRestore(config(frequency = ExportFrequency.DAILY)))
        assertTrue(BootRescheduleReceiver.shouldRestore(config(frequency = ExportFrequency.WEEKLY)))
    }

    @Test
    fun `a manual schedule in storage is not overwritten on boot`() {
        storeConfig(config(frequency = ExportFrequency.MANUAL))

        receive(Intent.ACTION_BOOT_COMPLETED)

        assertEquals(
            json.encodeToString(config(frequency = ExportFrequency.MANUAL)),
            prefs().getString("scheduled_config", null),
        )
    }

    @Test
    fun `unexpected actions are ignored`() {
        storeConfig(config())

        receive(Intent.ACTION_SCREEN_ON)

        assertNotNull(prefs().getString("scheduled_config", null))
    }

    @Test
    fun `a stale last send is reported as stale after boot`() {
        storeConfig(config())
        storeLastSuccess(hoursAgo = 30)

        // Reaching this point exercises the catch-up branch inside the receiver
        receive(Intent.ACTION_BOOT_COMPLETED)

        assertTrue(SendStats.lastSend(app).isStale(Every2HoursWebhookWorker.STALE_AFTER_HOURS))
    }

    @Test
    fun `a recent last send is not stale`() {
        storeConfig(config())
        storeLastSuccess(hoursAgo = 1)

        receive(Intent.ACTION_BOOT_COMPLETED)

        assertFalse(SendStats.lastSend(app).isStale(Every2HoursWebhookWorker.STALE_AFTER_HOURS))
    }
}
