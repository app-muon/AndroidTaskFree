// ui/SmokeTest.kt
package com.taskfree.app.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.taskfree.app.MainActivity
import com.taskfree.app.R
import com.taskfree.app.ResetAppStateRule
import com.taskfree.app.data.entities.Category
import com.taskfree.app.data.entities.Task
import com.taskfree.app.domain.model.TaskStatus
import com.taskfree.app.ui.task.components.SEARCH_TOGGLE_TAG
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class SmokeTest {

    private val reset = ResetAppStateRule()
    private val compose = createEmptyComposeRule()

    private val notifications: TestRule =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            RuleChain.emptyRuleChain()
        }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(reset).around(notifications).around(compose)

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun closeActivity() {
        scenario?.close()
    }

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun str(@StringRes id: Int, vararg args: Any): String = ctx.getString(id, *args)

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    /* ---------- helpers ---------- */

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForGone(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty() }

    private fun taskRow(text: String) = hasContentDescription(text, substring = true)

    /** The app force-shows a "create your first …" tip on empty lists; it covers the screen. */
    private fun dismissForcedTip() {
        val gotIt = hasText(str(R.string.got_it_confirmation))
        waitFor(gotIt)
        compose.onNode(gotIt).performClick()
        waitForGone(gotIt)
    }

    /**
     * The task screen debounces its "add your first task" tip with delay(300); under the
     * Compose test clock that delay can elapse before Room has loaded, so the tip may
     * appear over a non-empty list. Dismiss it if it shows up.
     */
    private fun dismissTipIfShown(timeoutMs: Long = 2_000) {
        val gotIt = hasText(str(R.string.got_it_confirmation))
        if (runCatching { waitFor(gotIt, timeoutMs) }.isSuccess) {
            compose.onNode(gotIt).performClick()
            waitForGone(gotIt)
        }
    }

    private fun seedCategory(title: String = "Home"): Int = reset.seed {
        categoryDao().insertAll(listOf(Category(title = title, color = 0xFF3F51B5))).single().toInt()
    }

    private fun seedTask(categoryId: Int, text: String, order: Int = 0): Int = reset.seed {
        taskDao().insert(
            Task(
                categoryId = categoryId,
                text = text,
                due = LocalDate.now(),
                singleCategoryPageOrder = order,
                allCategoryPageOrder = order
            )
        ).toInt()
    }

    /* ---------- tests ---------- */

    @Test
    fun firstLaunch_addCategoryFromEmptyState() {
        launch()
        waitFor(hasText(str(R.string.no_categories_yet)))
        dismissForcedTip()

        compose.onNodeWithText(str(R.string.add_category_button_label)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Groceries")
        compose.onNodeWithText(str(R.string.add_category_yes_dialog_button)).performClick()

        waitFor(hasText("Groceries"))
        assertEquals(
            listOf("Groceries"),
            runBlocking { reset.db().categoryDao().getAllNow().map { it.title } }
        )
    }

    @Test
    fun addTask_appearsInTodayList() {
        seedCategory()
        launch()
        dismissForcedTip()   // no tasks yet

        compose.onNodeWithText(str(R.string.add_task_button_label)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Buy milk")
        compose.onNodeWithText(str(R.string.save_yes_dialog_button)).performClick()

        waitFor(taskRow("Buy milk"))
        val saved = runBlocking { reset.db().taskDao().getAllNow() }.single()
        assertEquals("Buy milk", saved.text)
        assertEquals(LocalDate.now(), saved.due)
    }

    @Test
    fun markTaskDone_fromOptionsPanel() {
        val cat = seedCategory()
        val id = seedTask(cat, "Water plants")
        launch()
        waitFor(taskRow("Water plants"))
        dismissTipIfShown()

        compose.onNode(taskRow("Water plants")).performClick()
        val setDone = str(R.string.set_as_for_task_status, str(R.string.done_status))
        waitFor(hasText(setDone))
        compose.onNodeWithText(setDone).performClick()

        compose.waitUntil(10_000) {
            runBlocking { reset.db().taskDao().taskById(id)?.status } == TaskStatus.DONE
        }
        assertEquals(LocalDate.now(), runBlocking { reset.db().taskDao().taskById(id)!!.completedDate })
    }

    @Test
    fun search_filtersTheList() {
        val cat = seedCategory()
        seedTask(cat, "Buy milk", order = 0)
        seedTask(cat, "Email Bob", order = 1)
        launch()
        waitFor(taskRow("Buy milk"))
        waitFor(taskRow("Email Bob"))
        dismissTipIfShown()

        compose.onNodeWithTag(SEARCH_TOGGLE_TAG).performClick()
        waitFor(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextInput("MILK")

        waitForGone(taskRow("Email Bob"))
        waitFor(taskRow("Buy milk"))
    }
}
