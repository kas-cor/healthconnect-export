package com.healthconnect.export.util

import android.app.Application
import android.content.Context
import android.os.PowerManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@RunWith(MockitoJUnitRunner.Silent::class)
class BatteryOptimizationTest {
    private val context: Application = mock()

    @Test
    fun `a missing power manager counts as exempt`() {
        whenever(context.getSystemService(Context.POWER_SERVICE)).thenReturn(null)

        assertTrue(BatteryOptimization.isIgnored(context))
    }

    @Test
    fun `the power manager decides the exemption state`() {
        val powerManager = mock<PowerManager>()
        whenever(context.getSystemService(Context.POWER_SERVICE)).thenReturn(powerManager)

        whenever(powerManager.isIgnoringBatteryOptimizations(context.packageName)).thenReturn(false)
        assertFalse(BatteryOptimization.isIgnored(context))

        whenever(powerManager.isIgnoringBatteryOptimizations(context.packageName)).thenReturn(true)
        assertTrue(BatteryOptimization.isIgnored(context))
    }

    @Test
    fun `request intent is built for this package`() {
        assertNotNull(BatteryOptimization.requestIntent(context))
    }
}
