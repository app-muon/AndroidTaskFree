// ui/task/components/ReorderTest.kt
package com.taskfree.app.ui.task.components

import com.taskfree.app.data.entities.Task
import com.taskfree.app.testutil.task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReorderTest {

    private fun t(id: Int, order: Int) = task(id = id, categoryId = 1, text = "t$id", allOrder = order)

    private fun reorder(all: List<Task>, visible: List<Task>, from: Int, to: Int) =
        computeReorderUpdates(
            all, visible, from, to,
            getOrder = { it.allCategoryPageOrder },
            setOrder = { task, ord -> task.copy(allCategoryPageOrder = ord) }
        )

    private fun List<Task>.orders() = associate { it.id to it.allCategoryPageOrder }

    /** Applies updates on top of the full list and returns ids in final order. */
    private fun applied(all: List<Task>, updates: List<Task>): List<Int> {
        val byId = updates.associateBy { it.id }
        return all.map { byId[it.id] ?: it }.sortedBy { it.allCategoryPageOrder }.map { it.id }
    }

    @Test
    fun `moving down renumbers only the affected rows`() {
        val all = listOf(t(1, 0), t(2, 1), t(3, 2), t(4, 3))
        val updates = reorder(all, all, from = 0, to = 2)

        assertEquals(mapOf(2 to 0, 3 to 1, 1 to 2), updates.orders())
        assertEquals(listOf(2, 3, 1, 4), applied(all, updates))
    }

    @Test
    fun `hidden tasks keep their slots when the visible slice moves`() {
        val all = listOf(t(1, 0), t(2, 1), t(3, 2), t(4, 3), t(5, 4))
        val visible = listOf(t(1, 0), t(3, 2), t(5, 4))   // 2 and 4 are filtered out

        val updates = reorder(all, visible, from = 2, to = 0)

        assertEquals(listOf(5, 2, 1, 4, 3), applied(all, updates))
        assertEquals(setOf(1, 3, 5), updates.map { it.id }.toSet())
    }

    @Test
    fun `sparse orders are compacted to contiguous integers`() {
        val all = listOf(t(1, 0), t(2, 5), t(3, 10))
        val updates = reorder(all, all, from = 0, to = 1)

        assertEquals(mapOf(2 to 0, 1 to 1, 3 to 2), updates.orders())
    }

    @Test
    fun `input order does not matter`() {
        val sorted = listOf(t(1, 0), t(2, 1), t(3, 2))
        val shuffled = listOf(sorted[2], sorted[0], sorted[1])

        assertEquals(reorder(sorted, sorted, 2, 0), reorder(shuffled, shuffled.reversed(), 2, 0))
    }

    @Test
    fun `same position is a no-op`() {
        val all = listOf(t(1, 0), t(2, 7))
        assertTrue(reorder(all, all, from = 1, to = 1).isEmpty())
    }
}
