package com.taskfree.app.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskfree.app.R
import com.taskfree.app.domain.model.Recurrence
import com.taskfree.app.domain.model.RepeatRule
import com.taskfree.app.ui.task.components.RepeatDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

@RunWith(AndroidJUnit4::class)
class RepeatStepperUiTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun tapsRespectBothLimitsAndNeverOfferTextEntry() {
        var initialInterval by mutableIntStateOf(1)
        var saved: RepeatRule? = null
        compose.setContent {
            MaterialTheme {
                key(initialInterval) {
                    RepeatDialog(RepeatRule(Recurrence.DAILY, initialInterval), LocalDate.now(),
                        onDone = { rule, _ -> saved = rule }, onDismiss = {})
                }
            }
        }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithTag("repeat_decrement").assertIsNotEnabled()
        compose.onNodeWithTag("repeat_interval_unit").assertTextEquals("day")
        compose.onNodeWithTag("repeat_increment").performClick()
        compose.onNodeWithTag("repeat_interval").assertTextEquals("2")
        compose.onNodeWithTag("repeat_interval_unit").assertTextEquals("days")
        compose.onNodeWithTag("repeat_decrement").performClick()
        compose.onNodeWithTag("repeat_interval").assertTextEquals("1")
        compose.runOnIdle { initialInterval = 98 }
        compose.onNodeWithTag("repeat_increment").performClick()
        compose.onNodeWithTag("repeat_interval").assertTextEquals("99")
        compose.onNodeWithTag("repeat_increment").assertIsNotEnabled()
        compose.onNodeWithTag("repeat_done").performScrollTo().performClick()
        assertEquals(RepeatRule(Recurrence.DAILY, 99), saved)
    }

    @Test fun holdingRepeatsAndReleaseStopsWithoutAnExtraClick() {
        compose.setContent {
            MaterialTheme {
                RepeatDialog(RepeatRule(Recurrence.DAILY), LocalDate.now(), onDone = { _, _ -> }, onDismiss = {})
            }
        }
        fun interval() = compose.onNodeWithTag("repeat_interval").fetchSemanticsNode()
            .config[SemanticsProperties.Text].single().text.toInt()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("repeat_increment").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_200)
        // Render any interval change scheduled at the end of the clock advance.
        compose.mainClock.advanceTimeByFrame()
        val whileHeld = interval()
        assertTrue("A held press should advance more than once", whileHeld > 2)
        compose.onNodeWithTag("repeat_increment").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)
        assertEquals(whileHeld, interval())
        compose.onNodeWithTag("repeat_decrement").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(3_000)
        compose.onNodeWithTag("repeat_interval").assertTextEquals("1")
        compose.onNodeWithTag("repeat_decrement").assertIsNotEnabled()
        compose.onNodeWithTag("repeat_decrement").performTouchInput { up() }
        compose.mainClock.autoAdvance = true
    }

    @Test fun savingAndHistoryDisableBothButtons() {
        var saving by mutableStateOf(false)
        var readOnly by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                RepeatDialog(RepeatRule(Recurrence.WEEKLY, 2), LocalDate.now(),
                    saving = saving, readOnly = readOnly, onDone = { _, _ -> }, onDismiss = {})
            }
        }
        compose.runOnIdle { saving = true }
        compose.onNodeWithTag("repeat_decrement").assertIsNotEnabled()
        compose.onNodeWithTag("repeat_increment").assertIsNotEnabled()
        compose.runOnIdle { saving = false; readOnly = true }
        compose.onNodeWithTag("repeat_decrement").assertIsNotEnabled()
        compose.onNodeWithTag("repeat_increment").assertIsNotEnabled()
        compose.onNodeWithTag("repeat_done").assertDoesNotExist()
    }

    @Test fun existingDateStaysHiddenUnlessTheRuleNeedsCorrection() {
        val today = LocalDate.now()
        val compatibleFrequency = if (today.dayOfWeek.value > 5) Recurrence.WEEKENDS else Recurrence.WEEKDAYS
        val incompatibleDate = today.with(TemporalAdjusters.next(
            if (compatibleFrequency == Recurrence.WEEKENDS) DayOfWeek.MONDAY else DayOfWeek.SATURDAY))
        var savedDue: LocalDate? = null
        compose.setContent {
            MaterialTheme {
                RepeatDialog(RepeatRule(Recurrence.NONE), incompatibleDate,
                    onDone = { _, due -> savedDue = due }, onDismiss = {})
            }
        }
        fun select(resource: Int) = compose.onNode(hasText(context.getString(resource)) and hasClickAction())
            .performScrollTo().performClick()
        compose.onNodeWithTag("repeat_due").assertDoesNotExist()
        select(R.string.repeat_days)
        compose.onNodeWithTag("repeat_due").assertDoesNotExist()
        compose.onNodeWithTag("repeat_preview_date").assertExists()
        select(if (compatibleFrequency == Recurrence.WEEKENDS) R.string.recurrence_on_weekends else R.string.recurrence_on_weekdays)
        compose.onNodeWithTag("repeat_due").assertExists()
        compose.onNodeWithTag("repeat_done").assertIsNotEnabled()
        compose.onNodeWithTag("repeat_preview_date").assertDoesNotExist()
        select(R.string.today)
        compose.onNodeWithTag("repeat_done").assertIsEnabled()
        compose.onNodeWithTag("repeat_preview_date").assertExists()
        compose.onNodeWithTag("repeat_done").performScrollTo().performClick()
        assertEquals(today, savedDue)
    }
}
