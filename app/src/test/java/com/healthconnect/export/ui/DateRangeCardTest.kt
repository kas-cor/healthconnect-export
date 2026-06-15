package com.healthconnect.export.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.healthconnect.export.R
import com.healthconnect.export.ui.components.DateRangeCard
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Ignore("Requires Robolectric + Compose activity setup — WIP from stash")
class DateRangeCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `dateRangeCard shows health connect access limit warning text`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val warningText = context.getString(R.string.health_connect_access_limit)

        composeTestRule.setContent {
            DateRangeCard(
                startDate = LocalDate.now().minusDays(6),
                endDate = LocalDate.now(),
                onDateRangeChange = { _, _ -> },
                onStartDateChange = { },
                onEndDateChange = { }
            )
        }

        composeTestRule
            .onNodeWithText(warningText)
            .assertIsDisplayed()
    }

    @Test
    fun `dateRangeCard shows date picker buttons`() {
        composeTestRule.setContent {
            DateRangeCard(
                startDate = LocalDate.of(2026, 5, 1),
                endDate = LocalDate.of(2026, 5, 27),
                onDateRangeChange = { _, _ -> },
                onStartDateChange = { },
                onEndDateChange = { }
            )
        }

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val fromLabel = context.getString(R.string.from_label)
        val toLabel = context.getString(R.string.to_label)

        composeTestRule.onNodeWithText(fromLabel).assertIsDisplayed()
        composeTestRule.onNodeWithText(toLabel).assertIsDisplayed()
    }

    @Test
    fun `dateRangeCard shows preset period buttons`() {
        composeTestRule.setContent {
            DateRangeCard(
                startDate = LocalDate.now().minusDays(6),
                endDate = LocalDate.now(),
                onDateRangeChange = { _, _ -> },
                onStartDateChange = { },
                onEndDateChange = { }
            )
        }

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val days7 = context.getString(R.string.days_7)
        val days30 = context.getString(R.string.days_30)

        composeTestRule.onNodeWithText(days7).assertIsDisplayed()
        composeTestRule.onNodeWithText(days30).assertIsDisplayed()
    }
}
