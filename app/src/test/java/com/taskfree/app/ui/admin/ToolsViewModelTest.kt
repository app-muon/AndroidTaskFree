// ui/admin/ToolsViewModelTest.kt
package com.taskfree.app.ui.admin

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
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ToolsViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val today = LocalDate.of(2026, 10, 6)
    private lateinit var db: AppDatabase
    private lateinit var taskRepo: TaskRepository
    private lateinit var vm: ToolsViewModel

    @Before
    fun setUp() {
        db = inMemoryDb()
        taskRepo = TaskRepository(db, datesAt(today))
        vm = ToolsViewModel(taskRepo, CategoryRepository(db), main.dispatcher)
    }

    @After
    fun tearDown() {
        vm.viewModelScope.cancel()
        db.close()
    }

    @Test
    fun `archiving old completed tasks reports and refreshes`() = runTest {
        val cat = db.insertCategory()
        val old = db.insertTask(task(cat, "old", status = TaskStatus.DONE, completedDate = today.minusDays(1)))
        val refreshed = async(UnconfinedTestDispatcher(testScheduler)) { vm.refresh.first() }

        vm.archiveOldCompleted().join()

        refreshed.await()
        assertEquals(ToolsEvent.Archived, vm.uiState.value.lastEvent)
        assertTrue(taskRepo.taskById(old)!!.isArchived)
    }

    @Test
    fun `deleting archived tasks reports and refreshes`() = runTest {
        val cat = db.insertCategory()
        val keep = db.insertTask(task(cat, "keep"))
        db.insertTask(task(cat, "gone", isArchived = true))
        val refreshed = async(UnconfinedTestDispatcher(testScheduler)) { vm.refresh.first() }

        vm.deleteArchived().join()

        refreshed.await()
        assertEquals(ToolsEvent.Deleted, vm.uiState.value.lastEvent)
        assertEquals(listOf(keep), taskRepo.snapshot().map { it.id })
    }

    @Test
    fun `show-archived toggles`() {
        assertFalse(vm.uiState.value.showArchived)
        vm.toggleShowArchived()
        assertTrue(vm.uiState.value.showArchived)
        vm.toggleShowArchived()
        assertFalse(vm.uiState.value.showArchived)
    }
}
