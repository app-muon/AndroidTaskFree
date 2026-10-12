// ui/components/SortModeTest.kt
package com.taskfree.app.ui.components

import com.taskfree.app.data.entities.Category
import com.taskfree.app.ui.category.components.sortCategories
import org.junit.Assert.assertEquals
import org.junit.Test

class SortModeTest {

    private fun cycle(start: SortMode, modes: List<SortMode> = SortMode.entries) =
        generateSequence(start) { it.next(modes) }.take(modes.size + 1).toList()

    @Test
    fun `task toggle cycles custom, dates, A, Z and back`() {
        assertEquals(
            listOf(
                SortMode.USER, SortMode.DATE_ASC, SortMode.DATE_DESC,
                SortMode.ALPHA_ASC, SortMode.ALPHA_DESC, SortMode.USER
            ),
            cycle(SortMode.USER)
        )
    }

    @Test
    fun `category toggle skips the date modes`() {
        assertEquals(
            listOf(SortMode.USER, SortMode.ALPHA_ASC, SortMode.ALPHA_DESC, SortMode.USER),
            cycle(SortMode.USER, SortMode.CATEGORY_SORT_MODES)
        )
    }

    @Test
    fun `sortCategories orders by title for A and Z and keeps the custom order otherwise`() {
        val categories = listOf(
            Category(id = 1, title = "work", color = 0),
            Category(id = 2, title = "Errands", color = 0),
            Category(id = 3, title = "Home", color = 0),
        )

        assertEquals(listOf(2, 3, 1), sortCategories(categories, SortMode.ALPHA_ASC).map { it.id })
        assertEquals(listOf(1, 3, 2), sortCategories(categories, SortMode.ALPHA_DESC).map { it.id })
        assertEquals(listOf(1, 2, 3), sortCategories(categories, SortMode.USER).map { it.id })
    }
}
