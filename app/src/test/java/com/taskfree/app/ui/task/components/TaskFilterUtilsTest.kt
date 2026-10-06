// ui/task/components/TaskFilterUtilsTest.kt
package com.taskfree.app.ui.task.components

import com.taskfree.app.data.entities.Category
import com.taskfree.app.data.entities.Task
import com.taskfree.app.data.entities.TaskWithCategoryInfo
import com.taskfree.app.domain.model.TaskStatus
import com.taskfree.app.testutil.datesAt
import com.taskfree.app.testutil.task
import com.taskfree.app.ui.components.SortMode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class TaskFilterUtilsTest {

    private val home = Category(id = 1, title = "Home", color = 0)
    private val work = Category(id = 2, title = "Work", color = 0)
    private val allStatuses = TaskStatus.entries.toList()

    private fun row(task: Task) = TaskWithCategoryInfo(task, if (task.categoryId == 1) home else work)

    private val rows = listOf(
        row(task(id = 1, categoryId = 1, text = "Buy Milk", status = TaskStatus.TODO)),
        row(task(id = 2, categoryId = 1, text = "Clean kitchen", status = TaskStatus.DONE)),
        row(task(id = 3, categoryId = 2, text = "milk the budget", status = TaskStatus.IN_PROGRESS)),
        row(task(id = 4, categoryId = 2, text = "Email Bob", status = TaskStatus.PENDING)),
    )

    private fun List<TaskWithCategoryInfo>.ids() = map { it.task.id }

    @Test
    fun `no filters returns everything`() {
        assertEquals(listOf(1, 2, 3, 4), TaskFilterUtils.filterTasks(rows, TaskListState(), allStatuses).ids())
    }

    @Test
    fun `filters by category`() {
        val state = TaskListState(selectedCategoryId = 2)
        assertEquals(listOf(3, 4), TaskFilterUtils.filterTasks(rows, state, allStatuses).ids())
    }

    @Test
    fun `filters by visible status`() {
        val visible = listOf(TaskStatus.TODO, TaskStatus.PENDING)
        assertEquals(listOf(1, 4), TaskFilterUtils.filterTasks(rows, TaskListState(), visible).ids())
    }

    @Test
    fun `search is case-insensitive and ignores surrounding whitespace`() {
        val state = TaskListState(searchText = "  MILK ")
        assertEquals(listOf(1, 3), TaskFilterUtils.filterTasks(rows, state, allStatuses).ids())
    }

    @Test
    fun `blank search matches everything`() {
        val state = TaskListState(searchText = "   ")
        assertEquals(4, TaskFilterUtils.filterTasks(rows, state, allStatuses).size)
    }

    @Test
    fun `all filters combine`() {
        val state = TaskListState(selectedCategoryId = 1, searchText = "milk")
        assertEquals(listOf(1), TaskFilterUtils.filterTasks(rows, state, listOf(TaskStatus.TODO)).ids())
    }

    @Test
    fun `USER sort follows the chosen order property`() {
        val a = row(task(id = 1, categoryId = 1, allOrder = 2, singleOrder = 0))
        val b = row(task(id = 2, categoryId = 1, allOrder = 0, singleOrder = 2))
        val c = row(task(id = 3, categoryId = 1, allOrder = 1, singleOrder = 1))
        val input = listOf(a, b, c)

        assertEquals(
            listOf(2, 3, 1),
            TaskFilterUtils.sortTasks(input, SortMode.USER, OrderProperty.ALL_CATEGORY_PAGE_ORDERING).ids()
        )
        assertEquals(
            listOf(1, 3, 2),
            TaskFilterUtils.sortTasks(input, SortMode.USER, OrderProperty.SINGLE_CATEGORY_PAGE_ORDERING).ids()
        )
    }

    @Test
    fun `date sorts keep undated tasks last and break ties by text`() {
        val d1 = LocalDate.of(2026, 10, 1)
        val d2 = LocalDate.of(2026, 10, 2)
        val input = listOf(
            row(task(id = 1, categoryId = 1, text = "zebra", due = d1)),
            row(task(id = 2, categoryId = 1, text = "Apple", due = d1)),
            row(task(id = 3, categoryId = 1, text = "undated")),
            row(task(id = 4, categoryId = 1, text = "later", due = d2)),
        )
        val order = OrderProperty.ALL_CATEGORY_PAGE_ORDERING

        assertEquals(listOf(2, 1, 4, 3), TaskFilterUtils.sortTasks(input, SortMode.DATE_ASC, order).ids())
        assertEquals(listOf(4, 2, 1, 3), TaskFilterUtils.sortTasks(input, SortMode.DATE_DESC, order).ids())
    }

    @Test
    fun `quickDateKind picks set, postpone or move`() {
        val today = LocalDate.of(2026, 10, 6)
        val dp = datesAt(today)
        assertEquals(QuickDateKind.SET, quickDateKind(task(categoryId = 1), dp))
        assertEquals(QuickDateKind.POSTPONE, quickDateKind(task(categoryId = 1, due = today), dp))
        assertEquals(QuickDateKind.POSTPONE, quickDateKind(task(categoryId = 1, due = today.minusDays(4)), dp))
        assertEquals(QuickDateKind.MOVE, quickDateKind(task(categoryId = 1, due = today.plusDays(1)), dp))
    }

    @Test
    fun `mergeFullWithVisible swaps in the visible copies by id`() {
        val full = listOf(task(id = 1, categoryId = 1, text = "a"), task(id = 2, categoryId = 1, text = "b"))
        val visible = listOf(task(id = 2, categoryId = 1, text = "b-updated"))

        assertEquals(listOf("a", "b-updated"), mergeFullWithVisible(full, visible).map { it.text })
    }
}
