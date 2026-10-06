// ui/task/TaskViewModelTest.kt
package com.taskfree.app.ui.task

import android.app.AlarmManager
import android.app.Application
import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskfree.app.R
import com.taskfree.app.data.database.AppDatabase
import com.taskfree.app.data.repository.TaskRepository
import com.taskfree.app.domain.model.Recurrence
import com.taskfree.app.domain.model.TaskStatus
import com.taskfree.app.notifications.AlarmReceiver
import com.taskfree.app.testutil.MainDispatcherRule
import com.taskfree.app.testutil.MutableClock
import com.taskfree.app.testutil.clockAt
import com.taskfree.app.testutil.inMemoryDb
import com.taskfree.app.testutil.insertCategory
import com.taskfree.app.testutil.insertTask
import com.taskfree.app.testutil.task
import com.taskfree.app.ui.components.DueChoice
import com.taskfree.app.ui.components.NotificationOption
import com.taskfree.app.util.DateProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowToast
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Note: TaskViewModel.init starts an endless once-a-minute loop on viewModelScope,
 * so these tests never call advanceUntilIdle() (they use advanceTimeBy/runCurrent) and
 * run inside [vmTest], which cancels the loop before runTest's final drain.
 *
 * Reminder scheduling compares against the real Instant.now(), so the clock is anchored
 * to the real date and reminder fixtures sit days away from it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class TaskViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val alarms: ShadowAlarmManager get() = shadowOf(app.getSystemService(AlarmManager::class.java))

    private val realToday = LocalDate.now()
    private lateinit var clock: MutableClock
    private lateinit var db: AppDatabase
    private lateinit var repo: TaskRepository
    private lateinit var vm: TaskViewModel
    private var cat = 0

    @Before
    fun setUp() {
        TaskStatusFilter.resetForTesting()
        clock = clockAt(realToday)
        val dates = DateProvider(clock)
        db = inMemoryDb()
        repo = TaskRepository(db, dates)
        vm = TaskViewModel(app, repo, dates, main.dispatcher)
        cat = runBlocking { db.insertCategory() }
    }

    @After
    fun tearDown() {
        vm.viewModelScope.cancel()
        db.close()
    }

    /**
     * runTest drains its scheduler after the body, and the VM's day-change loop shares it,
     * so the loop must be cancelled inside runTest or the drain never ends.
     */
    private fun vmTest(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    private fun localAt(date: LocalDate, hour: Int) =
        date.atTime(LocalTime.of(hour, 0)).atZone(ZoneId.systemDefault()).toInstant()

    private fun alarmTaskIds() = alarms.scheduledAlarms.map {
        shadowOf(it.operation).savedIntent.getIntExtra(AlarmReceiver.EXTRA_TASK_ID, -1)
    }

    private fun latestToast(): String? {
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowToast.getTextOfLatestToast()
    }

    private suspend fun awaitState(predicate: (TaskUiState) -> Boolean) =
        vm.uiState.first { !it.isInitialLoadPending && predicate(it) }

    private fun TestScope.keepCollecting() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    }

    private suspend fun addDaily(due: LocalDate): Int {
        vm.add("Stretch", DueChoice.Other(due), Recurrence.DAILY, cat, NotificationOption.Morning).join()
        return repo.snapshot().single().id
    }

    /* ---------- reminders ---------- */

    @Test
    fun `adding a task with a future reminder schedules an alarm`() = vmTest {
        val due = realToday.plusDays(10)

        vm.add("Dentist", DueChoice.Other(due), Recurrence.NONE, cat, NotificationOption.Morning).join()

        val task = repo.snapshot().single()
        assertEquals(localAt(due, 9), task.reminderTime)
        assertEquals(listOf(task.id), alarmTaskIds())
        assertEquals(localAt(due, 9).toEpochMilli(), alarms.scheduledAlarms.single().triggerAtMs)
    }

    @Test
    fun `a past reminder is kept but only warned about`() = vmTest {
        val due = LocalDate.of(2020, 1, 1)

        vm.add("Old", DueChoice.Other(due), Recurrence.NONE, cat, NotificationOption.Noon).join()

        assertEquals(localAt(due, 12), repo.snapshot().single().reminderTime)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertEquals(app.getString(R.string.notification_in_past), latestToast())
    }

    @Test
    fun `completing a recurring task moves the alarm to the next occurrence`() = vmTest {
        val due = realToday.plusDays(3)
        val id = addDaily(due)

        vm.updateStatus(id, TaskStatus.DONE).join()

        val next = repo.snapshot().single { it.id != id }
        assertEquals(due.plusDays(1), next.due)
        assertEquals(listOf(next.id), alarmTaskIds())
        assertEquals(localAt(due.plusDays(1), 9).toEpochMilli(), alarms.scheduledAlarms.single().triggerAtMs)
    }

    @Test
    fun `reopening a completed task restores its alarm and drops the spawned one`() = vmTest {
        val due = realToday.plusDays(3)
        val id = addDaily(due)
        vm.updateStatus(id, TaskStatus.DONE).join()

        vm.updateStatus(id, TaskStatus.TODO).join()

        assertEquals(listOf(id), repo.snapshot().map { it.id })
        assertEquals(listOf(id), alarmTaskIds())
        assertEquals(localAt(due, 9).toEpochMilli(), alarms.scheduledAlarms.single().triggerAtMs)
    }

    @Test
    fun `clearing the due date clears the reminder and its alarm`() = vmTest {
        vm.add("Call", DueChoice.Other(realToday.plusDays(5)), Recurrence.NONE, cat, NotificationOption.Morning).join()
        val id = repo.snapshot().single().id

        vm.applyEdits(id, TaskViewModel.TaskEdits(due = FieldEdit.Clear)).join()

        val task = repo.taskById(id)!!
        assertNull(task.due)
        assertNull(task.reminderTime)
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test
    fun `changing the reminder time reschedules the alarm`() = vmTest {
        val due = realToday.plusDays(5)
        vm.add("Call", DueChoice.Other(due), Recurrence.NONE, cat, NotificationOption.Morning).join()
        val id = repo.snapshot().single().id

        vm.applyEdits(id, TaskViewModel.TaskEdits(notify = FieldEdit.Set(NotificationOption.Afternoon))).join()

        assertEquals(localAt(due, 15), repo.taskById(id)!!.reminderTime)
        assertEquals(localAt(due, 15).toEpochMilli(), alarms.scheduledAlarms.single().triggerAtMs)
    }

    @Test
    fun `deleting a task cancels its alarm`() = vmTest {
        vm.add("Call", DueChoice.Other(realToday.plusDays(5)), Recurrence.NONE, cat, NotificationOption.Morning).join()
        val task = repo.snapshot().single()

        vm.deletePermanently(task).join()

        assertTrue(repo.snapshot().isEmpty())
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    /* ---------- list state ---------- */

    @Test
    fun `status visibility and the archived switch drive the list`() = vmTest {
        val todo = db.insertTask(task(cat, "todo", allOrder = 0))
        val done = db.insertTask(task(cat, "done", status = TaskStatus.DONE, completedDate = realToday, allOrder = 1))
        val archived = db.insertTask(task(cat, "archived", isArchived = true, allOrder = 2))
        keepCollecting()

        awaitState { s -> s.tasks.map { it.task.id } == listOf(todo, done) }

        vm.toggleStatusVisibility(TaskStatus.DONE)
        awaitState { s -> s.tasks.map { it.task.id } == listOf(todo) }

        vm.setShowArchived(true)
        val state = awaitState { s -> s.filter.showArchived && s.tasks.map { it.task.id } == listOf(archived) }
        assertTrue(TaskStatus.DONE !in state.visibleStatuses)
    }

    @Test
    fun `a today filter follows the date when the day changes`() = vmTest {
        keepCollecting()
        vm.setDate(realToday)
        awaitState { it.filter.date == realToday }

        clock.setDate(realToday.plusDays(1))
        advanceTimeBy(60_001)
        runCurrent()

        awaitState { it.filter.date == realToday.plusDays(1) }
    }

    @Test
    fun `a filter on another date is left alone when the day changes`() = vmTest {
        val someday = realToday.minusDays(5)
        keepCollecting()
        vm.setDate(someday)
        awaitState { it.filter.date == someday }

        clock.setDate(realToday.plusDays(1))
        advanceTimeBy(60_001)
        runCurrent()

        assertEquals(someday, vm.uiState.value.filter.date)
    }

    @Test
    fun `refreshToday snaps a stale date filter to today`() = vmTest {
        keepCollecting()
        vm.setDate(realToday.minusDays(1))
        vm.refreshToday()
        awaitState { it.filter.date == realToday }

        vm.setDate(null)
        vm.refreshToday()
        assertNull(awaitState { it.filter.date == null }.filter.date)
    }

    @Test
    fun `dragging in the all-categories page persists contiguous orders`() = vmTest {
        val a = db.insertTask(task(cat, "a", allOrder = 0))
        val b = db.insertTask(task(cat, "b", allOrder = 1))
        val c = db.insertTask(task(cat, "c", allOrder = 2))
        val all = repo.snapshot()
        var completed = false

        vm.moveInAllCategoryPage(all, all, from = 0, to = 2) { completed = true }.join()

        assertTrue(completed)
        assertEquals(listOf(b, c, a), repo.snapshot().sortedBy { it.allCategoryPageOrder }.map { it.id })
        assertEquals(listOf(0, 1, 2), repo.snapshot().map { it.allCategoryPageOrder }.sorted())
    }
}
