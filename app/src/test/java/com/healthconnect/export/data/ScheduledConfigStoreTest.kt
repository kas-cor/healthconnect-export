package com.healthconnect.export.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduledConfigStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun prefs() = context.getSharedPreferences("healthconnect_export_prefs", Context.MODE_PRIVATE)

    private fun config(
        frequency: ExportFrequency = ExportFrequency.DAILY,
        webhookUrl: String = "https://hooks.example.com/data",
    ) = ExportConfig(
        enabledTypes = setOf(HealthDataType.STEPS, HealthDataType.SLEEP),
        frequency = frequency,
        autoSyncDrive = true,
        webhookUrl = webhookUrl,
        autoSendWebhookEvery2Hours = true,
        scheduleHour = 7,
    )

    @Before
    fun clearPrefs() {
        prefs().edit().clear().commit()
    }

    @Test
    fun `nothing stored means no config`() {
        assertNull(ScheduledConfigStore.load(context))
    }

    @Test
    fun `a saved config round-trips`() {
        ScheduledConfigStore.save(context, config())

        assertEquals(config(), ScheduledConfigStore.load(context))
    }

    @Test
    fun `saving the identical config does not rewrite it`() {
        ScheduledConfigStore.save(context, config())
        val stored = prefs().getString("scheduled_config", null)

        ScheduledConfigStore.save(context, config())

        assertEquals(stored, prefs().getString("scheduled_config", null))
    }

    @Test
    fun `saving a changed config overwrites the stored one`() {
        ScheduledConfigStore.save(context, config())
        ScheduledConfigStore.save(context, config(webhookUrl = "https://hooks.example.com/other"))

        assertEquals("https://hooks.example.com/other", ScheduledConfigStore.load(context)?.webhookUrl)
    }

    @Test
    fun `clearing removes the stored config`() {
        ScheduledConfigStore.save(context, config())

        ScheduledConfigStore.clear(context)

        assertNull(ScheduledConfigStore.load(context))
    }

    @Test
    fun `corrupt json is reported as no config`() {
        prefs().edit().putString("scheduled_config", "{not json").commit()

        assertNull(ScheduledConfigStore.load(context))
    }

    @Test
    fun `unknown fields are tolerated`() {
        prefs()
            .edit()
            .putString(
                "scheduled_config",
                """{"enabledTypes":["STEPS"],"frequency":"DAILY","autoSyncDrive":false,"unknownFutureField":42}""",
            ).commit()

        val loaded = ScheduledConfigStore.load(context)

        assertEquals(ExportFrequency.DAILY, loaded?.frequency)
        assertFalse(loaded!!.autoSyncDrive)
    }

    @Test
    fun `a context without preferences stores nothing and does not throw`() {
        val mockContext = org.mockito.kotlin.mock<Context>()

        ScheduledConfigStore.save(mockContext, config())

        assertNull(ScheduledConfigStore.load(mockContext))
    }
}
