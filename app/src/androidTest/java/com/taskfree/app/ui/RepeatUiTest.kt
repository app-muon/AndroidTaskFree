package com.taskfree.app.ui

import android.Manifest
import androidx.test.rule.GrantPermissionRule
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskfree.app.MainActivity
import com.taskfree.app.R
import com.taskfree.app.ResetAppStateRule
import com.taskfree.app.data.entities.Category
import com.taskfree.app.data.entities.Task
import com.taskfree.app.domain.model.Recurrence
import com.taskfree.app.domain.model.TaskStatus
import com.taskfree.app.notifications.AlarmReceiver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class RepeatUiTest {
    private val reset = ResetAppStateRule()
    private val compose = createEmptyComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(reset)
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)).around(compose)
    private var scenario: ActivityScenario<MainActivity>? = null
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun str(id: Int) = context.getString(id)
    @After fun close() { scenario?.close() }

    private fun launch(history: Boolean = false, due: LocalDate? = LocalDate.now(), corrupt: Boolean = false) {
        reset.seed {
            categoryDao().insertAll(listOf(Category(1, "Home", 0xFF336699)))
            taskDao().insert(Task(1, 1, "Repeat test", due = due, singleCategoryPageOrder = 0,
                status = if (history) TaskStatus.DONE else TaskStatus.TODO,
                recurrence = if (history || corrupt) Recurrence.WEEKLY else Recurrence.NONE,
                recurrenceInterval = if (corrupt) 0 else if (history) 3 else 1,
                baseDate = if (history) LocalDate.now() else null))
            if (history) taskDao().insert(Task(2, 1, "Current occurrence", due = LocalDate.now().plusDays(21),
                singleCategoryPageOrder = 1, recurrence = Recurrence.WEEKLY, recurrenceInterval = 3,
                baseDate = LocalDate.now().plusDays(21), sourceTaskId = 1))
        }
        // Also exercises the entry point used by notifications.
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java)
            .putExtra(AlarmReceiver.EXTRA_TASK_ID, 1))
        waitFor(hasTestTag("open_repeat"))
    }
    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(15_000) {
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
    }
    private fun repeatText(id: Int) = compose.onNode(hasText(str(id)) and hasClickAction() and hasAnyAncestor(hasTestTag("repeat_dialog")))
    private fun openRepeat() {
        compose.onNodeWithTag("open_repeat").performScrollTo().performClick()
        waitFor(hasTestTag("repeat_dialog"))
    }
    private fun done() {
        compose.onNodeWithTag("repeat_done").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("repeat_dialog")).fetchSemanticsNodes().isEmpty() }
    }
    private fun task(id: Int = 1) = runBlocking { reset.db().taskDao().taskById(id)!! }
    private fun screenshot(name: String) {
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun repeatDraftRotatesCancelsAndSavesOnlyOnDone() {
        launch()
        openRepeat()
        repeatText(R.string.repeat_months).performClick()
        compose.onNodeWithTag("repeat_increment").performClick()
        screenshot("repeat-edit.png")
        scenario!!.recreate()
        waitFor(hasTestTag("repeat_interval"))
        compose.onNodeWithTag("repeat_interval").assertTextContains("2")
        assertEquals(Recurrence.NONE, task().recurrence)
        repeatText(R.string.cancel_no_dialog_button).performScrollTo().performClick()
        assertEquals(Recurrence.NONE, task().recurrence)
        openRepeat()
        repeatText(R.string.repeat_weeks).performClick()
        repeat(2) { compose.onNodeWithTag("repeat_increment").performClick() }
        done()
        assertEquals(Recurrence.WEEKLY, task().recurrence)
        assertEquals(3, task().recurrenceInterval)
        compose.onNodeWithTag("task-title-row").assertExists()
    }

    @Test fun incompatibleDatesBlockSavingAndFailedSaveKeepsDraft() {
        launch()
        openRepeat()
        repeatText(R.string.repeat_days).performClick()
        compose.onNodeWithTag("repeat_decrement").assertIsNotEnabled()
        compose.onNodeWithTag("repeat_due").assertDoesNotExist()
        val incompatible = if (LocalDate.now().dayOfWeek.value > 5)
            R.string.recurrence_on_weekdays else R.string.recurrence_on_weekends
        repeatText(incompatible).performScrollTo().performClick()
        compose.onNodeWithTag("repeat_done").assertIsNotEnabled()
        repeatText(R.string.repeat_days).performScrollTo().performClick()
        compose.onNodeWithTag("repeat_increment").performClick()
        reset.seed { openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_repeat BEFORE UPDATE ON Task BEGIN SELECT RAISE(ABORT, 'injected'); END") }
        compose.onNodeWithTag("repeat_done").performScrollTo().performClick()
        waitFor(hasText(str(R.string.repeat_save_failed)))
        compose.onNodeWithTag("repeat_interval").assertTextContains("2")
        assertEquals(Recurrence.NONE, task().recurrence)
        reset.seed { openHelper.writableDatabase.execSQL("DROP TRIGGER fail_repeat") }
        done()
        assertEquals(2, task().recurrenceInterval)
    }

    @Test fun historyShowsExplicitCurrentLinkAndPreservesHistoricalRule() {
        launch(history = true)
        openRepeat()
        compose.onNodeWithTag("repeat_done").assertDoesNotExist()
        repeatText(R.string.repeat_edit_current).performScrollTo().performClick()
        waitFor(hasText("Current occurrence", substring = true))
        repeatText(R.string.repeat_days).performClick()
        compose.onNodeWithTag("repeat_decrement").performClick()
        done()
        assertEquals(Recurrence.WEEKLY, task(1).recurrence)
        assertEquals(3, task(1).recurrenceInterval)
        assertEquals(Recurrence.DAILY, task(2).recurrence)
        assertEquals(2, task(2).recurrenceInterval)
    }

    @Test fun openingRepeatKeepsUnfinishedNoteAndTitleSaveOrdering() {
        launch()
        compose.onNodeWithTag("add-note").performScrollTo().performClick()
        compose.onNodeWithTag("note-composer").performTextInput("Unfinished note")
        openRepeat()
        repeatText(R.string.repeat_days).performClick()
        compose.onNodeWithTag("repeat_increment").performClick()
        done()
        compose.onNodeWithTag("note-composer").assertTextContains("Unfinished note")
        assertTrue(runBlocking { reset.db().taskNoteDao().getAllNow().isEmpty() })
    }

    @Test fun undoingAutomaticRepeatDateKeepsExistingUndatedTaskUndated() {
        launch(due = null)
        openRepeat()
        repeatText(R.string.repeat_days).performClick()
        repeatText(R.string.repeat_none).performClick()
        done()
        assertNull(task().due)
        assertEquals(Recurrence.NONE, task().recurrence)
    }

    @Test fun staleSaveKeepsBlockedDraftAndExplicitCurrentLinkWithoutTransferringIt() {
        launch()
        openRepeat()
        repeatText(R.string.repeat_days).performClick()
        repeat(2) { compose.onNodeWithTag("repeat_increment").performClick() }
        // Another operation completes the occurrence and establishes the current successor.
        reset.seed {
            taskDao().update(taskDao().taskById(1)!!.copy(status = TaskStatus.DONE))
            taskDao().insert(Task(2, 1, "New current task", due = LocalDate.now().plusDays(2),
                singleCategoryPageOrder = 1, sourceTaskId = 1))
        }
        compose.onNodeWithTag("repeat_done").performScrollTo().performClick()
        waitFor(hasText(str(R.string.repeat_edit_current)))
        compose.onNodeWithTag("repeat_done").assertIsNotEnabled()
        compose.onNodeWithTag("repeat_interval").assertTextEquals("3")
        compose.onNodeWithText(str(R.string.repeat_stale_draft)).assertExists()
        scenario!!.recreate()
        waitFor(hasText(str(R.string.repeat_edit_current)))
        compose.onNodeWithTag("repeat_done").assertIsNotEnabled()
        compose.onNodeWithTag("repeat_interval").assertTextEquals("3")
        assertEquals(Recurrence.NONE, task(2).recurrence)
        repeatText(R.string.repeat_edit_current).performScrollTo().performClick()
        waitFor(hasText("New current task", substring = true))
        repeatText(R.string.repeat_none).assertIsSelected()
        repeatText(R.string.repeat_weeks).performClick()
        done()
        assertEquals(Recurrence.NONE, task(1).recurrence)
        assertEquals(Recurrence.WEEKLY, task(2).recurrence)
        assertEquals(1, task(2).recurrenceInterval)
    }

    @Test fun notificationsWaitForParentAfterRepeatClosesAndLatestTapWins() {
        launch()
        reset.seed {
            taskDao().insert(Task(2, 1, "First notification", singleCategoryPageOrder = 1))
            taskDao().insert(Task(3, 1, "Latest notification", singleCategoryPageOrder = 2))
        }
        openRepeat()
        repeatText(R.string.repeat_days).performClick()
        repeat(2) { compose.onNodeWithTag("repeat_increment").performClick() }
        compose.runOnIdle { MainActivity.pendingIntent(context, 2).send() }
        compose.waitUntil(10_000) {
            var pending: Int? = null
            scenario!!.onActivity { pending = it.notificationTaskId }
            pending == 2
        }
        compose.runOnIdle { MainActivity.pendingIntent(context, 3).send() }
        compose.waitUntil(10_000) {
            var pending: Int? = null
            scenario!!.onActivity { pending = it.notificationTaskId }
            pending == 3
        }
        compose.waitForIdle()
        scenario!!.recreate()
        waitFor(hasTestTag("repeat_interval"))
        compose.onNodeWithTag("repeat_interval").assertTextEquals("3")
        repeatText(R.string.cancel_no_dialog_button).performScrollTo().performClick()
        compose.onNodeWithTag("task-title-row").assertExists()
        scenario!!.onActivity { assertEquals(3, it.notificationTaskId) }
        compose.onNodeWithText(str(R.string.close_yes_dialog_button)).performClick()
        waitFor(hasText("Latest notification", substring = true) and
            (hasTestTag("task-title-row") or hasAnyAncestor(hasTestTag("task-title-row"))))
        scenario!!.onActivity { assertNull(it.notificationTaskId) }
        assertEquals(Recurrence.NONE, task(1).recurrence)
    }

    @Test fun consumedNotificationDoesNotReopenClosedPanelAfterRecreationOrHistoryLaunch() {
        launch()
        compose.onNodeWithText(str(R.string.close_yes_dialog_button)).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("open_repeat")).fetchSemanticsNodes().isEmpty() }
        scenario!!.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("open_repeat").assertDoesNotExist()
        scenario!!.close()
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java)
            .putExtra(AlarmReceiver.EXTRA_TASK_ID, 1)
            .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
        waitFor(hasText("Repeat test"))
        compose.onNodeWithTag("open_repeat").assertDoesNotExist()
        // A genuinely fresh notification still opens the task.
        scenario!!.close()
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java)
            .putExtra(AlarmReceiver.EXTRA_TASK_ID, 1))
        waitFor(hasTestTag("open_repeat"))
    }

    @Test fun navigatingToCategoryAndBackDoesNotRestoreClosedOptions() {
        launch()
        compose.onNodeWithContentDescription("${str(R.string.go_to_category_action).trim()} Home")
            .performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("open_repeat").assertDoesNotExist()
        androidx.test.espresso.Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithTag("open_repeat").assertDoesNotExist()
        scenario!!.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("open_repeat").assertDoesNotExist()
    }

    @Test fun existingTodayDateSelectsTodayChipAndCorruptRuleStillDisplays() {
        launch(corrupt = true)
        compose.onNodeWithText(str(R.string.repeat_invalid)).performScrollTo().assertExists()
        compose.onNodeWithTag("open_due").performScrollTo().performClick()
        compose.onNodeWithTag("due_option_TODAY").assertIsSelected()
        compose.onNodeWithTag("due_option_OTHER").assertIsNotSelected()
        compose.onNodeWithText(str(R.string.close_yes_dialog_button)).performClick()
        waitFor(hasText("Repeat test"))
        compose.onNode(hasText(str(R.string.repeat_invalid), substring = true)).assertExists()
    }
}
