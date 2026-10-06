// data/repository/CategoryRepositoryTest.kt
package com.taskfree.app.data.repository

import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskfree.app.data.database.AppDatabase
import com.taskfree.app.data.entities.Category
import com.taskfree.app.domain.model.Recurrence
import com.taskfree.app.domain.model.TaskInput
import com.taskfree.app.domain.model.TaskStatus
import com.taskfree.app.testutil.assertFails
import com.taskfree.app.testutil.datesAt
import com.taskfree.app.testutil.inMemoryDb
import com.taskfree.app.testutil.insertCategory
import com.taskfree.app.testutil.insertTask
import com.taskfree.app.testutil.task
import com.taskfree.app.ui.theme.categoryPalette
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class CategoryRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: CategoryRepository

    @Before
    fun setUp() {
        db = inMemoryDb()
        repo = CategoryRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun visibleTitles() = repo.observeAllCategories().first().map { it.title }

    @Test
    fun `createCategory trims, caps and appends`() = runTest {
        repo.createCategory("  Groceries  ")
        repo.createCategory("y".repeat(50))

        val cats = repo.observeAllCategories().first()
        assertEquals(listOf("Groceries", "y".repeat(35)), cats.map { it.title })
        assertEquals(listOf(0, 1), cats.map { it.categoryPageOrder })
    }

    @Test
    fun `createCategory picks an unsigned colour from the palette`() = runTest {
        repo.createCategory("Colourful")

        val palette = categoryPalette.map { it.toArgb().toLong() and 0xFFFFFFFFL }
        val color = repo.snapshot().single().color
        assertTrue("$color not in palette", color in palette)
        assertTrue(color > 0)
    }

    @Test
    fun `blank titles are rejected`() = runTest {
        assertFails<IllegalArgumentException> { repo.createCategory("   ") }
        db.insertCategory("keep")
        assertFails<IllegalArgumentException> { repo.updateCategoryTitle(repo.snapshot().single(), "") }
        assertEquals("keep", repo.snapshot().single().title)
    }

    @Test
    fun `deleting a category with tasks archives them and soft-deletes it`() = runTest {
        val id = db.insertCategory("Work")
        val taskId = db.insertTask(task(id, "report"))
        val cat = repo.snapshot().single()

        repo.deleteCategoryWithTasks(cat)

        assertTrue(db.taskDao().taskById(taskId)!!.isArchived)
        assertTrue(repo.snapshot().single().isDeleted)
        assertTrue(visibleTitles().isEmpty())
    }

    @Test
    fun `deleting an empty category removes it outright`() = runTest {
        db.insertCategory("Empty")

        repo.deleteCategoryWithTasks(repo.snapshot().single())

        assertTrue(repo.snapshot().isEmpty())
    }

    @Test
    fun `new categories go after the last active one`() = runTest {
        db.insertCategory("a", order = 0)
        db.insertCategory("ghost", order = 9, isDeleted = true)

        repo.createCategory("b")

        assertEquals(1, repo.snapshot().single { it.title == "b" }.categoryPageOrder)
    }

    @Test
    fun `incomplete counts are keyed by category`() = runTest {
        val a = db.insertCategory("a")
        val b = db.insertCategory("b")
        db.insertTask(task(a, "1"))
        db.insertTask(task(a, "2"))
        db.insertTask(task(b, "done", status = TaskStatus.DONE, completedDate = LocalDate.of(2026, 10, 6)))

        assertEquals(mapOf(a to 2), repo.observeIncompleteTaskCounts().first())
    }

    @Test
    fun `rename, recolour and reorder persist`() = runTest {
        db.insertCategory("a", order = 0)
        db.insertCategory("b", order = 1)
        val (a, b) = repo.snapshot().sortedBy { it.categoryPageOrder }

        repo.updateCategoryTitle(a, " Alpha ")
        repo.updateCategoryColor(b, 0xFF00FF00)
        repo.updateCategoryOrder(listOf(b.copy(categoryPageOrder = 0, color = 0xFF00FF00), a.copy(title = "Alpha", categoryPageOrder = 1)))

        assertEquals(listOf("b", "Alpha"), visibleTitles())
        assertEquals(0xFF00FF00, repo.snapshot().single { it.title == "b" }.color)
    }

    @Test
    fun `updates to missing categories fail`() = runTest {
        val ghost = Category(id = 404, title = "ghost", color = 0)
        assertFails<IllegalArgumentException> { repo.updateCategoryTitle(ghost, "x") }
        assertFails<IllegalArgumentException> { repo.updateCategoryColor(ghost, 1) }
        assertFails<IllegalArgumentException> { repo.updateCategoryOrder(listOf(ghost)) }
    }

    @Test
    fun `deleted categories are not visible to the task repository either`() = runTest {
        val id = db.insertCategory("Gone")
        repo.deleteCategoryWithTasks(repo.snapshot().single())
        val taskRepo = TaskRepository(db, datesAt(LocalDate.of(2026, 10, 6)))

        assertFails<IllegalArgumentException> {
            taskRepo.createTask(TaskInput("x", null, Recurrence.NONE, id))
        }
    }
}
