package com.taskfree.app.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskfree.app.ResetAppStateRule
import com.taskfree.app.data.entities.Category
import com.taskfree.app.data.entities.Task
import com.taskfree.app.data.entities.TaskNote
import com.taskfree.app.data.repository.TaskRepository
import com.taskfree.app.ui.task.TaskViewModel
import com.taskfree.app.ui.task.TaskVmFactory
import com.taskfree.app.ui.task.components.OrderProperty
import com.taskfree.app.ui.task.components.SEARCH_TOGGLE_TAG
import com.taskfree.app.ui.task.components.TaskListContent
import com.taskfree.app.ui.task.components.TaskScreenConfig
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class TaskListSearchUiTest {
    private val reset = ResetAppStateRule()
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(reset).around(compose)

    @Test fun typingPastATitleMatchKeepsTheMatchingNoteTask() {
        reset.seed {
            categoryDao().insertAll(listOf(Category(1, "Home", 0xFF336699)))
            taskDao().insert(Task(1, 1, "This is a task", singleCategoryPageOrder = 0))
            taskNoteDao().insert(TaskNote(1, 1, "ss", Instant.parse("2026-10-16T09:00:00Z")))
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = TaskRepository(reset.db())
        lateinit var taskVm: TaskViewModel
        compose.setContent {
            // Share the list's owner so the test can await the real asynchronous search.
            taskVm = viewModel(factory = TaskVmFactory(context, repo))
            MaterialTheme {
                TaskListContent(
                    noTaskMessage = "No tasks",
                    orderProperty = OrderProperty.ALL_CATEGORY_PAGE_ORDERING,
                    config = TaskScreenConfig(),
                    onDueChange = {},
                    onCategoryChange = {},
                    onClickTask = { _, _ -> }
                )
            }
        }
        val row = hasContentDescription("This is a task", substring = true)
        compose.waitUntil(10_000) { compose.onAllNodes(row).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(SEARCH_TOGGLE_TAG).performClick()

        // The title and note both match "s", so the completed result leaves the list equal.
        compose.onNode(hasSetTextAction()).performTextInput("s")
        compose.waitUntil(10_000) {
            taskVm.searchResults.value.let { it.query == "s" && it.taskIds == setOf(1) }
        }
        compose.onNode(row).assertIsDisplayed()

        // Only the note matches "ss". Sorting must read the latest filtering state.
        compose.onNode(hasSetTextAction()).performTextInput("s")
        compose.waitUntil(10_000) {
            taskVm.searchResults.value.let { it.query == "ss" && 1 in it.noteTargets }
        }
        compose.onNode(row).assertIsDisplayed()
    }
}
