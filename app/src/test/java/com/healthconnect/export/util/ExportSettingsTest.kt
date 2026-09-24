package com.healthconnect.export.util

import android.app.Application
import android.content.Context
import com.healthconnect.export.data.ExportFormat
import com.healthconnect.export.data.ExportFrequency
import com.healthconnect.export.data.HealthDataType
import com.healthconnect.export.testing.FakeSharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@RunWith(MockitoJUnitRunner.Silent::class)
class ExportSettingsTest {
    private lateinit var context: Application
    private lateinit var prefs: FakeSharedPreferences

    @Before
    fun setup() {
        prefs = FakeSharedPreferences()
        context = mock()
        whenever(context.getSharedPreferences(any(), any())).thenReturn(prefs)
    }

    @Test
    fun `defaults mirror the app defaults`() {
        val config = ExportSettings.loadConfig(context)

        assertEquals(HealthDataType.entries.toSet(), config.enabledTypes)
        assertEquals(ExportFrequency.DAILY, config.frequency)
        assertEquals(ExportFormat.JSON, config.exportFormat)
        assertTrue(config.autoSyncDrive)
        assertEquals("", config.webhookUrl)
        assertEquals("", config.webhookAuthToken)
        assertFalse(config.autoSendWebhook)
        assertFalse(config.autoSendWebhookEvery2Hours)
        assertNull(config.selectedSourcePackage)
        assertNull(config.scheduleHour)
        assertFalse(ExportSettings.hasWebhook(config))
    }

    @Test
    fun `persisted values are restored`() {
        prefs
            .edit()
            .putString(ExportSettings.KEY_WEBHOOK_URL, "https://example.com/hook")
            .putString(ExportSettings.KEY_WEBHOOK_TOKEN, "token")
            .putBoolean(ExportSettings.KEY_AUTO_SEND_WEBHOOK, true)
            .putBoolean(ExportSettings.KEY_AUTO_SEND_WEBHOOK_EVERY_2_HOURS, true)
            .putBoolean(ExportSettings.KEY_AUTO_SYNC_DRIVE, false)
            .putStringSet(ExportSettings.KEY_SELECTED_TYPES, mutableSetOf("STEPS", "SLEEP"))
            .putString(ExportSettings.KEY_SOURCE_PACKAGE, "com.mi.health")
            .putString(ExportSettings.KEY_EXPORT_FORMAT, "CSV")
            .putInt(ExportSettings.KEY_SCHEDULE_HOUR, 7)
            .putString(ExportSettings.KEY_FREQUENCY, "WEEKLY")
            .apply()

        val config = ExportSettings.loadConfig(context)

        assertEquals(setOf(HealthDataType.STEPS, HealthDataType.SLEEP), config.enabledTypes)
        assertEquals(ExportFrequency.WEEKLY, config.frequency)
        assertEquals(ExportFormat.CSV, config.exportFormat)
        assertFalse(config.autoSyncDrive)
        assertEquals("https://example.com/hook", config.webhookUrl)
        assertEquals("token", config.webhookAuthToken)
        assertTrue(config.autoSendWebhook)
        assertTrue(config.autoSendWebhookEvery2Hours)
        assertEquals("com.mi.health", config.selectedSourcePackage)
        assertEquals(7, config.scheduleHour)
        assertTrue(ExportSettings.hasWebhook(config))
    }

    @Test
    fun `unknown enum values and out of range hours fall back to defaults`() {
        prefs
            .edit()
            .putString(ExportSettings.KEY_EXPORT_FORMAT, "XML")
            .putString(ExportSettings.KEY_FREQUENCY, "YEARLY")
            .putInt(ExportSettings.KEY_SCHEDULE_HOUR, 42)
            .putStringSet(ExportSettings.KEY_SELECTED_TYPES, mutableSetOf("NOT_A_TYPE"))
            .apply()

        val config = ExportSettings.loadConfig(context)

        assertEquals(ExportFormat.JSON, config.exportFormat)
        assertEquals(ExportFrequency.DAILY, config.frequency)
        assertNull(config.scheduleHour)
        assertEquals(HealthDataType.entries.toSet(), config.enabledTypes)
    }

    @Test
    fun `frequency round trips through prefs`() {
        ExportSettings.saveFrequency(context, ExportFrequency.MANUAL)

        assertEquals(ExportFrequency.MANUAL, ExportSettings.loadFrequency(context))
    }

    @Test
    fun `frequency is read from a plain context too`() {
        val plainContext = mock<Context>()
        whenever(plainContext.getSharedPreferences(any(), any())).thenReturn(prefs)

        assertEquals(ExportFrequency.DAILY, ExportSettings.loadFrequency(plainContext))
        assertEquals(ExportSettings.PREFS_NAME, "healthconnect_export_prefs")
        assertNotNull(ExportSettings.prefs(context))
    }
}
