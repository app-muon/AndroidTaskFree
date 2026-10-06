// ui/components/ChoiceSaversTest.kt
package com.taskfree.app.ui.components

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import com.taskfree.app.testutil.AppDateProviderRule
import com.taskfree.app.testutil.task
import com.taskfree.app.ui.task.components.TaskListState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ChoiceSaversTest {

    private val today = LocalDate.of(2026, 10, 6)

    @get:Rule
    val dateRule = AppDateProviderRule(today)

    private val scope = object : SaverScope {
        override fun canBeSaved(value: Any) = true
    }

    private fun <T : Any, S : Any> Saver<T, S>.roundTrip(value: T): T? =
        restore(with(this) { scope.save(value) }!!)

    @Test
    fun `DueChoice from maps relative days`() {
        assertEquals(DueChoice.Today, DueChoice.from(today))
        assertEquals(DueChoice.Tomorrow, DueChoice.from(today.plusDays(1)))
        assertEquals(DueChoice.Plus2, DueChoice.from(today.plusDays(2)))
        assertEquals(DueChoice.Other(today.plusDays(3)), DueChoice.from(today.plusDays(3)))
        assertEquals(DueChoice.Other(today.minusDays(1)), DueChoice.from(today.minusDays(1)))
    }

    @Test
    fun `quick DueChoices resolve to concrete dates`() {
        assertEquals(today, DueChoice.Today.date)
        assertEquals(today.plusDays(1), DueChoice.Tomorrow.date)
        assertEquals(today.plusDays(2), DueChoice.Plus2.date)
    }

    @Test
    fun `DueChoice fromTask uses None for undated tasks`() {
        assertEquals(DueChoice.None, DueChoice.fromTask(task(categoryId = 1)))
        assertEquals(DueChoice.Tomorrow, DueChoice.fromTask(task(categoryId = 1, due = today.plusDays(1))))
    }

    @Test
    fun `DueChoiceSaver round-trips every variant`() {
        listOf(
            DueChoice.None, DueChoice.All, DueChoice.Today, DueChoice.Tomorrow, DueChoice.Plus2,
            DueChoice.Other(LocalDate.of(2027, 1, 15)), DueChoice.Other(null)
        ).forEach { assertEquals(it, DueChoiceSaver.roundTrip(it)) }
    }

    @Test
    fun `NotificationOptionSaver round-trips every variant`() {
        listOf(
            NotificationOption.None, NotificationOption.Morning, NotificationOption.Noon,
            NotificationOption.Afternoon, NotificationOption.Other(LocalTime.of(18, 45)),
            NotificationOption.Other(null)
        ).forEach { assertEquals(it, NotificationOptionSaver.roundTrip(it)) }
    }

    @Test
    fun `TaskListState Saver round-trips`() {
        val state = TaskListState(
            selectedCategoryId = 7,
            searchText = " milk ",
            searchVisible = true,
            dueChoice = DueChoice.Other(LocalDate.of(2027, 3, 1)),
            sortMode = SortMode.DATE_DESC
        )
        assertEquals(state, TaskListState.Saver.roundTrip(state))
    }

    @Test
    fun `TaskListState Saver falls back to defaults on corrupt input`() {
        assertEquals(TaskListState(), TaskListState.Saver.restore(listOf("garbage")))
    }
}
