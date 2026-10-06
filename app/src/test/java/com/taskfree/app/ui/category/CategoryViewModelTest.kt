// ui/category/CategoryViewModelTest.kt
package com.taskfree.app.ui.category

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskfree.app.data.database.AppDatabase
import com.taskfree.app.data.repository.CategoryRepository
import com.taskfree.app.data.repository.TaskRepository
import com.taskfree.app.domain.model.TaskStatus
import com.taskfree.app.testutil.MainDispatcherRule
import com.taskfree.app.testutil.datesAt
import com.taskfree.app.testutil.inMemoryDb
import com.taskfree.app.testutil.insertCategory
import com.taskfree.app.testutil.insertTask
import com.taskfree.app.testutil.task
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CategoryViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val today = LocalDate.of(2026, 10, 6)
    private lateinit var db: AppDatabase
    private lateinit var catRepo: CategoryRepository
    private lateinit var vm: CategoryViewModel

    @Before
    fun setUp() {
        db = inMemoryDb()
        catRepo = CategoryRepository(db)
        vm = CategoryViewModel(catRepo, TaskRepository(db, datesAt(today)), main.dispatcher)
    }

    @After
    fun tearDown() {
        vm.viewModelScope.cancel()
        db.close()
    }

    private suspend fun awaitState(predicate: (CategoryUiState) -> Boolean) =
        vm.uiState.first { !it.isInitialLoadPending && predicate(it) }

    private val CategoryUiState.titles get() = categories.map { it.title }

    /** Keeps uiState hot so `uiState.value` is live (as it is while the screen is shown). */
    private fun TestScope.keepCollecting() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    }

    @Test
    fun `state combines categories with incomplete counts`() = runTest {
        val a = db.insertCategory("a", order = 0)
        db.insertCategory("b", order = 1)
        db.insertTask(task(a, "open"))
        db.insertTask(task(a, "done", status = TaskStatus.DONE, completedDate = today))

        val state = awaitState { it.categories.size == 2 }

        assertEquals(listOf("a", "b"), state.titles)
        assertEquals(mapOf(a to 1), state.incompleteCounts)
    }

    @Test
    fun `add, rename and delete update the state`() = runTest {
        vm.add("Groceries").join()
        val cat = awaitState { it.titles == listOf("Groceries") }.categories.single()

        vm.rename(cat, "Food").join()
        awaitState { it.titles == listOf("Food") }

        vm.delete(cat).join()
        awaitState { it.categories.isEmpty() }
    }

    @Test
    fun `dragging previews the order and persists it on drop`() = runTest {
        db.insertCategory("a", order = 0)
        db.insertCategory("b", order = 1)
        db.insertCategory("c", order = 2)
        keepCollecting()
        awaitState { it.categories.size == 3 }

        vm.onDragStart()
        vm.onDragMove(0, 2)
        val dragging = awaitState { it.isDragging }
        assertEquals(listOf("b", "c", "a"), dragging.titles)
        assertEquals(listOf("a", "b", "c"), catRepo.snapshot().sortedBy { it.categoryPageOrder }.map { it.title })

        vm.onDragEnd()
        awaitState { !it.isDragging && it.titles == listOf("b", "c", "a") }
        val stored = catRepo.snapshot().sortedBy { it.categoryPageOrder }
        assertEquals(listOf("b", "c", "a"), stored.map { it.title })
        assertEquals(listOf(0, 1, 2), stored.map { it.categoryPageOrder })
    }

    @Test
    fun `moves outside a drag or with bad indices are ignored`() = runTest {
        db.insertCategory("a", order = 0)
        db.insertCategory("b", order = 1)
        keepCollecting()
        awaitState { it.categories.size == 2 }

        vm.onDragMove(0, 1)
        assertEquals(listOf("a", "b"), vm.uiState.value.titles)

        vm.onDragStart()
        vm.onDragMove(0, 5)
        vm.onDragMove(-1, 0)
        vm.onDragMove(1, 1)
        assertEquals(listOf("a", "b"), awaitState { it.isDragging }.titles)
    }

    @Test
    fun `archiveCompleted and updateColor reach the database`() = runTest {
        val id = db.insertCategory("a")
        val done = db.insertTask(task(id, "done", status = TaskStatus.DONE, completedDate = today))
        val cat = catRepo.snapshot().single()

        vm.archiveCompleted(cat).join()
        vm.updateColor(cat, 0xFF00FF00).join()

        assertTrue(db.taskDao().taskById(done)!!.isArchived)
        assertEquals(0xFF00FF00, catRepo.snapshot().single().color)
    }
}
