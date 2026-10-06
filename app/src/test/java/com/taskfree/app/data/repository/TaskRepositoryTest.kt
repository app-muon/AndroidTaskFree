// data/repository/TaskRepositoryTest.kt
package com.taskfree.app.data.repository

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
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class TaskRepositoryTest {

    private val today = LocalDate.of(2026, 10, 6)
    private val tomorrow = today.plusDays(1)

    private lateinit var db: AppDatabase
    private lateinit var repo: TaskRepository
    private val dao get() = db.taskDao()

    @Before
    fun setUp() {
        db = inMemoryDb()
        repo = TaskRepository(db, datesAt(today))
    }

    @After
    fun tearDown() = db.close()

    private fun at9(date: LocalDate) = date.atTime(LocalTime.of(9, 0)).atZone(ZoneId.systemDefault()).toInstant()

    private suspend fun dailyTask(cat: Int, text: String = "Stretch") =
        repo.createTask(TaskInput(text, today, Recurrence.DAILY, cat, at9(today)))

    /* ---------- createTask ---------- */

    @Test
    fun `createTask trims, flattens newlines and caps the title`() = runTest {
        val cat = db.insertCategory()
        val id = repo.createTask(TaskInput("  Buy\r\nmilk\nnow  ", null, Recurrence.NONE, cat))
        assertEquals("Buy milk now", repo.taskById(id)!!.text)

        val longId = repo.createTask(TaskInput("x".repeat(150), null, Recurrence.NONE, cat))
        assertEquals(100, repo.taskById(longId)!!.text.length)
    }

    @Test
    fun `createTask stores a recurring task with its base date`() = runTest {
        val cat = db.insertCategory()
        val task = repo.taskById(dailyTask(cat))!!

        assertEquals(today, task.due)
        assertEquals(today, task.baseDate)
        assertEquals(TaskStatus.TODO, task.status)
        assertEquals(at9(today), task.reminderTime)
    }

    @Test
    fun `createTask rejects invalid input`() = runTest {
        val cat = db.insertCategory()
        val deleted = db.insertCategory(isDeleted = true)

        assertFails<IllegalArgumentException> {
            repo.createTask(TaskInput("   ", null, Recurrence.NONE, cat))
        }
        assertFails<IllegalArgumentException> {
            repo.createTask(TaskInput("Recurring", null, Recurrence.WEEKLY, cat))
        }
        assertFails<IllegalArgumentException> {
            repo.createTask(TaskInput("Into deleted", null, Recurrence.NONE, deleted))
        }
        assertFails<IllegalArgumentException> {
            repo.createTask(TaskInput("Into missing", null, Recurrence.NONE, 999))
        }
        assertTrue(repo.snapshot().isEmpty())
    }

    @Test
    fun `createTask appends to both page orders`() = runTest {
        val a = db.insertCategory("a")
        val b = db.insertCategory("b")
        val first = repo.createTask(TaskInput("1", null, Recurrence.NONE, a))
        val second = repo.createTask(TaskInput("2", null, Recurrence.NONE, a))
        val third = repo.createTask(TaskInput("3", null, Recurrence.NONE, b))

        assertEquals(listOf(0, 1, 0), listOf(first, second, third).map { repo.taskById(it)!!.singleCategoryPageOrder })
        assertEquals(listOf(0, 1, 2), listOf(first, second, third).map { repo.taskById(it)!!.allCategoryPageOrder })
    }

    /* ---------- updateTaskStatus ---------- */

    @Test
    fun `completing a recurring task spawns the next occurrence at the same local time`() = runTest {
        val cat = db.insertCategory()
        val id = dailyTask(cat)

        val result = repo.updateTaskStatus(id, TaskStatus.DONE)

        val done = repo.taskById(id)!!
        assertEquals(TaskStatus.DONE, done.status)
        assertEquals(today, done.completedDate)

        val next = repo.taskById(result.nextCreatedId!!)!!
        assertEquals("Stretch", next.text)
        assertEquals(tomorrow, next.due)
        assertEquals(tomorrow, next.baseDate)
        assertEquals(TaskStatus.TODO, next.status)
        assertEquals(at9(tomorrow), next.reminderTime)
        assertNull(result.nextDeletedId)
    }

    @Test
    fun `undoing completion removes the spawned occurrence`() = runTest {
        val cat = db.insertCategory()
        val id = dailyTask(cat)
        val nextId = repo.updateTaskStatus(id, TaskStatus.DONE).nextCreatedId!!

        val result = repo.updateTaskStatus(id, TaskStatus.TODO)

        assertEquals(nextId, result.nextDeletedId)
        assertNull(repo.taskById(nextId))
        assertNull(repo.taskById(id)!!.completedDate)
    }

    @Test
    fun `done, undo, done leaves exactly one next occurrence`() = runTest {
        val cat = db.insertCategory()
        val id = dailyTask(cat)

        repo.updateTaskStatus(id, TaskStatus.DONE)
        repo.updateTaskStatus(id, TaskStatus.IN_PROGRESS)
        repo.updateTaskStatus(id, TaskStatus.DONE)

        assertEquals(1, repo.snapshot().count { it.due == tomorrow })
    }

    @Test
    fun `an existing next occurrence is reused, not duplicated`() = runTest {
        val cat = db.insertCategory()
        val id = dailyTask(cat)
        db.insertTask(task(cat, "Stretch", due = tomorrow, recurrence = Recurrence.DAILY))

        val result = repo.updateTaskStatus(id, TaskStatus.DONE)

        assertNull(result.nextCreatedId)
        assertEquals(1, repo.snapshot().count { it.due == tomorrow })
    }

    @Test
    fun `completing an archived recurring task does not spawn`() = runTest {
        val cat = db.insertCategory()
        val id = db.insertTask(task(cat, "old", due = today, recurrence = Recurrence.DAILY, isArchived = true))

        val result = repo.updateTaskStatus(id, TaskStatus.DONE)

        assertNull(result.nextCreatedId)
        assertEquals(TaskStatus.DONE, repo.taskById(id)!!.status)
        assertEquals(1, repo.snapshot().size)
    }

    @Test
    fun `non-recurring status changes set and clear the completed date`() = runTest {
        val cat = db.insertCategory()
        val id = repo.createTask(TaskInput("Once", null, Recurrence.NONE, cat))

        assertEquals(UpdateResult(), repo.updateTaskStatus(id, TaskStatus.DONE))
        assertEquals(today, repo.taskById(id)!!.completedDate)

        repo.updateTaskStatus(id, TaskStatus.PENDING)
        assertEquals(TaskStatus.PENDING, repo.taskById(id)!!.status)
        assertNull(repo.taskById(id)!!.completedDate)
    }

    @Test
    fun `live tasks in deleted categories and missing ids are ignored`() = runTest {
        val deleted = db.insertCategory(isDeleted = true)
        val id = db.insertTask(task(deleted, "stuck"))

        assertEquals(UpdateResult(), repo.updateTaskStatus(id, TaskStatus.DONE))
        assertEquals(TaskStatus.TODO, repo.taskById(id)!!.status)
        assertEquals(UpdateResult(), repo.updateTaskStatus(12345, TaskStatus.DONE))
    }

    /* ---------- archiving ---------- */

    @Test
    fun `archiving one occurrence completes it and spawns the next`() = runTest {
        val cat = db.insertCategory()
        val id = dailyTask(cat)

        val nextId = repo.archiveSingleOccurrence(repo.taskById(id)!!)

        val archived = repo.taskById(id)!!
        assertTrue(archived.isArchived)
        assertEquals(TaskStatus.DONE, archived.status)
        assertEquals(today, archived.completedDate)
        assertEquals(tomorrow, repo.taskById(nextId!!)!!.due)
    }

    @Test
    fun `archiving a non-recurring occurrence only archives it`() = runTest {
        val cat = db.insertCategory()
        val id = repo.createTask(TaskInput("Once", today, Recurrence.NONE, cat))

        assertNull(repo.archiveSingleOccurrence(repo.taskById(id)!!))

        val archived = repo.taskById(id)!!
        assertTrue(archived.isArchived)
        assertEquals(TaskStatus.TODO, archived.status)
        assertEquals(1, repo.snapshot().size)
    }

    @Test
    fun `archiveTasksCompletedBeforeToday uses the injected date`() = runTest {
        val cat = db.insertCategory()
        val old = db.insertTask(task(cat, "old", status = TaskStatus.DONE, completedDate = today.minusDays(1)))
        val fresh = db.insertTask(task(cat, "fresh", status = TaskStatus.DONE, completedDate = today))

        repo.archiveTasksCompletedBeforeToday()

        assertTrue(repo.taskById(old)!!.isArchived)
        assertFalse(repo.taskById(fresh)!!.isArchived)
    }

    @Test
    fun `archiveCompletedInCategory leaves other categories alone`() = runTest {
        val a = db.insertCategory("a")
        val b = db.insertCategory("b")
        val doneA = db.insertTask(task(a, "a", status = TaskStatus.DONE, completedDate = today))
        val doneB = db.insertTask(task(b, "b", status = TaskStatus.DONE, completedDate = today))

        repo.archiveCompletedInCategory(a)

        assertTrue(repo.taskById(doneA)!!.isArchived)
        assertFalse(repo.taskById(doneB)!!.isArchived)
    }

    /* ---------- editing ---------- */

    @Test
    fun `updateTaskDetails tracks base date only for recurring tasks`() = runTest {
        val cat = db.insertCategory()
        val id = repo.createTask(TaskInput("Plan", today, Recurrence.NONE, cat))
        val due = today.plusDays(5)

        repo.updateTaskDetails(repo.taskById(id)!!, " Plan week ", due, Recurrence.WEEKLY, cat, null)
        repo.taskById(id)!!.let {
            assertEquals("Plan week", it.text)
            assertEquals(due, it.baseDate)
            assertEquals(Recurrence.WEEKLY, it.recurrence)
        }

        repo.updateTaskDetails(repo.taskById(id)!!, "Plan week", due, Recurrence.NONE, cat, null)
        assertNull(repo.taskById(id)!!.baseDate)
    }

    @Test
    fun `live tasks cannot move into a deleted category`() = runTest {
        val cat = db.insertCategory()
        val deleted = db.insertCategory(isDeleted = true)
        val id = repo.createTask(TaskInput("Stay", null, Recurrence.NONE, cat))

        assertFails<IllegalArgumentException> {
            repo.updateTaskDetails(repo.taskById(id)!!, "Stay", null, Recurrence.NONE, deleted, null)
        }
        assertEquals(cat, repo.taskById(id)!!.categoryId)
    }

    @Test
    fun `saveTask can only unarchive into an active category`() = runTest {
        val cat = db.insertCategory()
        val deleted = db.insertCategory(isDeleted = true)
        val ok = db.insertTask(task(cat, "ok", isArchived = true))
        val stuck = db.insertTask(task(deleted, "stuck", isArchived = true))

        repo.saveTask(repo.taskById(ok)!!.copy(isArchived = false))
        assertFalse(repo.taskById(ok)!!.isArchived)

        assertFails<IllegalArgumentException> {
            repo.saveTask(repo.taskById(stuck)!!.copy(isArchived = false))
        }
    }

    @Test
    fun `reindexAllTaskPageOrders closes gaps per category`() = runTest {
        val cat = db.insertCategory()
        val a = db.insertTask(task(cat, "a", singleOrder = 3))
        val b = db.insertTask(task(cat, "b", singleOrder = 10))
        val c = db.insertTask(task(cat, "c", singleOrder = 7))

        repo.reindexAllTaskPageOrders()

        assertEquals(listOf(0, 2, 1), listOf(a, b, c).map { repo.taskById(it)!!.singleCategoryPageOrder })
    }

    /* ---------- deleting / replacing ---------- */

    @Test
    fun `deleting archived tasks purges emptied soft-deleted categories`() = runTest {
        val active = db.insertCategory("active")
        val deleted = db.insertCategory("deleted", isDeleted = true)
        val keep = db.insertTask(task(active, "keep"))
        db.insertTask(task(active, "gone", isArchived = true))
        db.insertTask(task(deleted, "gone too", isArchived = true))

        repo.deleteAllArchivedTasks()

        assertEquals(listOf(keep), repo.snapshot().map { it.id })
        assertEquals(listOf(active), db.categoryDao().getAllNow().map { it.id })
    }

    @Test
    fun `deleteTaskPermanently removes the row`() = runTest {
        val cat = db.insertCategory()
        val id = db.insertTask(task(cat))

        repo.deleteTaskPermanently(repo.taskById(id)!!)

        assertNull(repo.taskById(id))
    }

    @Test
    fun `replaceAll swaps the whole dataset`() = runTest {
        val old = db.insertCategory("old")
        db.insertTask(task(old, "old task"))

        val cats = listOf(
            Category(id = 10, title = "new", color = 1, categoryPageOrder = 0),
            Category(id = 11, title = "empty deleted", color = 1, isDeleted = true)
        )
        val tasks = listOf(task(10, "new task", id = 100))
        repo.replaceAll(cats, tasks)

        assertEquals(listOf("new task"), repo.snapshot().map { it.text })
        // the soft-deleted category had no tasks, so it is purged
        assertEquals(listOf(10), db.categoryDao().getAllNow().map { it.id })
    }

    @Test
    fun `replaceAll rejects live tasks in deleted categories`() = runTest {
        val existing = db.insertTask(task(db.insertCategory(), "existing"))
        val cats = listOf(Category(id = 10, title = "deleted", color = 1, isDeleted = true))

        assertFails<IllegalArgumentException> {
            repo.replaceAll(cats, listOf(task(10, "live", id = 100)))
        }
        assertNotNull(repo.taskById(existing))
    }
}
