// ui/admin/ToolsViewModelTest.kt
package com.taskfree.app.ui.admin

import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskfree.app.data.database.AppDatabase
import com.taskfree.app.data.repository.CategoryRepository
import com.taskfree.app.data.repository.TaskRepository
import com.taskfree.app.domain.model.Recurrence
import com.taskfree.app.domain.model.TaskStatus
import com.taskfree.app.testutil.MainDispatcherRule
import com.taskfree.app.testutil.datesAt
import com.taskfree.app.testutil.inMemoryDb
import com.taskfree.app.testutil.insertCategory
import com.taskfree.app.testutil.insertTask
import com.taskfree.app.testutil.task
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import com.taskfree.app.R
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
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
    val main = MainDispatcherRule(StandardTestDispatcher())

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
        assertEquals(ToolsEvent.Archived(1), vm.events.first())
        assertTrue(taskRepo.taskById(old)!!.isArchived)
    }

    @Test
    fun `archiving old completed repeats preserves one-offs and reports and refreshes`() = runTest {
        val cat = db.insertCategory()
        val repeat = db.insertTask(task(
            cat, "Repeat", recurrence = Recurrence.DAILY, status = TaskStatus.DONE,
            completedDate = today.minusDays(1)
        ))
        val oneOff = db.insertTask(task(
            cat, "One-off", status = TaskStatus.DONE, completedDate = today.minusDays(1)
        ))
        val refreshed = async(UnconfinedTestDispatcher(testScheduler)) { vm.refresh.first() }

        vm.archiveOldCompletedRepeats().join()

        refreshed.await()
        assertEquals(ToolsEvent.Archived(1), vm.events.first())
        assertTrue(taskRepo.taskById(repeat)!!.isArchived)
        assertFalse(taskRepo.taskById(oneOff)!!.isArchived)
    }

    @Test
    fun `deleting archived tasks reports and refreshes`() = runTest {
        val cat = db.insertCategory()
        val keep = db.insertTask(task(cat, "keep"))
        db.insertTask(task(cat, "gone", isArchived = true))
        val refreshed = async(UnconfinedTestDispatcher(testScheduler)) { vm.refresh.first() }

        vm.deleteArchived().join()

        refreshed.await()
        assertEquals(ToolsEvent.Deleted, vm.events.first())
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

    @Test
    fun `identical zero counts are buffered once each without replay`() = runTest {
        vm.archiveOldCompleted().join()
        vm.archiveOldCompletedRepeats().join()
        assertEquals(listOf(ToolsEvent.Archived(0), ToolsEvent.Archived(0)), vm.events.take(2).toList())
        val events = mutableListOf<ToolsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
        runCurrent()
        assertTrue(events.isEmpty())
    }

    @Test
    fun `consecutive positive counts each produce an event and refresh`() = runTest {
        val category = db.insertCategory()
        val refreshes = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.refresh.toList(refreshes) }
        repeat(2) {
            db.insertTask(task(category, status = TaskStatus.DONE, completedDate = today.minusDays(1)))
            vm.archiveOldCompleted().join()
        }
        assertEquals(listOf(ToolsEvent.Archived(1), ToolsEvent.Archived(1)), vm.events.take(2).toList())
        assertEquals(2, refreshes.size)
    }

    @Test
    fun `all failed bulk actions emit failure without success or refresh`() = runTest {
        val cat = db.insertCategory()
        db.insertTask(task(cat, status = TaskStatus.DONE, completedDate = today.minusDays(1),
            recurrence = Recurrence.DAILY))
        db.insertTask(task(cat, isArchived = true))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_update BEFORE UPDATE ON Task BEGIN SELECT RAISE(ABORT, 'injected'); END")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_delete BEFORE DELETE ON Task BEGIN SELECT RAISE(ABORT, 'injected'); END")
        val refreshes = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.refresh.toList(refreshes) }
        vm.archiveOldCompleted().join()
        vm.archiveOldCompletedRepeats().join()
        vm.deleteArchived().join()
        assertEquals(List(3) { ToolsEvent.Failed }, vm.events.take(3).toList())
        assertTrue(refreshes.isEmpty())
    }

    @Test
    fun `cancellation does not become failure or refresh`() = runTest {
        val events = mutableListOf<ToolsEvent>()
        val refreshes = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.refresh.toList(refreshes) }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transaction = launch(Dispatchers.IO) {
            db.withTransaction {
                entered.complete(Unit)
                release.await()
            }
        }
        try {
            entered.await()
            val operation = vm.archiveOldCompleted()
            runCurrent() // The action starts, then waits for the held Room transaction.
            assertFalse(operation.isCompleted)
            operation.cancel()
            // The blocked UPDATE cannot observe cancellation until the transaction ends.
            release.complete(Unit)
            operation.join()
            assertTrue(operation.isCancelled)
        } finally {
            release.complete(Unit)
            transaction.join()
        }
        assertTrue(events.isEmpty())
        assertTrue(refreshes.isEmpty())
    }

    @Test
    fun `archive counts are localized including zero and plural`() {
        val context: android.content.Context = androidx.test.core.app.ApplicationProvider.getApplicationContext()
        for ((language, expected) in listOf(
            "en" to listOf("Archived 0 tasks", "Archived 1 task", "Archived 12 tasks"),
            "es" to listOf("Se han archivado 0 tareas", "Se ha archivado 1 tarea", "Se han archivado 12 tareas")
        )) {
            val config = android.content.res.Configuration(context.resources.configuration)
            config.setLocale(java.util.Locale.forLanguageTag(language))
            val resources = context.createConfigurationContext(config).resources
            assertEquals(expected, listOf(0, 1, 12).map { resources.getQuantityString(R.plurals.archived_task_count, it, it) })
        }
    }
}
