// data/database/TaskDaoTest.kt
package com.taskfree.app.data.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskfree.app.domain.model.Recurrence
import com.taskfree.app.domain.model.TaskStatus
import com.taskfree.app.testutil.inMemoryDb
import com.taskfree.app.testutil.insertCategory
import com.taskfree.app.testutil.insertTask
import com.taskfree.app.testutil.task
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class TaskDaoTest {

    private lateinit var db: AppDatabase
    private val dao get() = db.taskDao()
    private val today = LocalDate.of(2026, 10, 6)

    @Before
    fun setUp() {
        db = inMemoryDb()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun listIds(date: LocalDate?, archived: Boolean = false) =
        dao.taskListForDate(date, archived).first().map { it.task.id }

    @Test
    fun `date list shows overdue, due and completed-today tasks`() = runTest {
        val cat = db.insertCategory()
        val overdue = db.insertTask(task(cat, "overdue", due = today.minusDays(3), allOrder = 0))
        val dueToday = db.insertTask(task(cat, "today", due = today, allOrder = 1))
        db.insertTask(task(cat, "future", due = today.plusDays(1), allOrder = 2))
        db.insertTask(task(cat, "undated", allOrder = 3))
        val doneToday = db.insertTask(
            task(cat, "done today", status = TaskStatus.DONE, completedDate = today, allOrder = 4)
        )
        db.insertTask(
            task(
                cat, "done yesterday", due = today.minusDays(3), status = TaskStatus.DONE,
                completedDate = today.minusDays(1), allOrder = 5
            )
        )

        assertEquals(listOf(overdue, dueToday, doneToday), listIds(today))
    }

    @Test
    fun `null date lists every live task in page order`() = runTest {
        val cat = db.insertCategory()
        val b = db.insertTask(task(cat, "b", allOrder = 1))
        val a = db.insertTask(task(cat, "a", due = today.plusDays(9), allOrder = 0))

        assertEquals(listOf(a, b), listIds(null))
    }

    @Test
    fun `archived flag splits the list`() = runTest {
        val cat = db.insertCategory()
        val live = db.insertTask(task(cat, "live"))
        val archived = db.insertTask(task(cat, "archived", isArchived = true))

        assertEquals(listOf(live), listIds(null, archived = false))
        assertEquals(listOf(archived), listIds(null, archived = true))
    }

    @Test
    fun `tasks in deleted categories are hidden unless archived`() = runTest {
        val active = db.insertCategory("active")
        val deleted = db.insertCategory("deleted", isDeleted = true)
        val visible = db.insertTask(task(active, "visible"))
        db.insertTask(task(deleted, "orphan"))
        val archivedOrphan = db.insertTask(task(deleted, "archived orphan", isArchived = true))

        assertEquals(listOf(visible), listIds(null))
        assertEquals(listOf(archivedOrphan), listIds(null, archived = true))
    }

    @Test
    fun `list rows carry their category`() = runTest {
        val cat = db.insertCategory("Home")
        db.insertTask(task(cat))

        assertEquals("Home", dao.taskListForDate(null, false).first().single().category.title)
    }

    @Test
    fun `incomplete counts skip done and archived tasks`() = runTest {
        val a = db.insertCategory("a")
        val b = db.insertCategory("b")
        db.insertTask(task(a, "todo"))
        db.insertTask(task(a, "in progress", status = TaskStatus.IN_PROGRESS))
        db.insertTask(task(a, "done", status = TaskStatus.DONE, completedDate = today))
        db.insertTask(task(b, "archived", isArchived = true))
        db.insertTask(task(b, "pending", status = TaskStatus.PENDING))

        val counts = dao.countNotDoneByCategory().first().associate { it.categoryId to it.count }
        assertEquals(mapOf(a to 2, b to 1), counts)
    }

    @Test
    fun `archiveOldCompletedTasks only archives tasks completed before today`() = runTest {
        val cat = db.insertCategory()
        val old = db.insertTask(task(cat, "old", status = TaskStatus.DONE, completedDate = today.minusDays(1)))
        val fresh = db.insertTask(task(cat, "fresh", status = TaskStatus.DONE, completedDate = today))
        val open = db.insertTask(task(cat, "open"))

        dao.archiveOldCompletedTasks(today)

        assertEquals(true, dao.taskById(old)!!.isArchived)
        assertEquals(false, dao.taskById(fresh)!!.isArchived)
        assertEquals(false, dao.taskById(open)!!.isArchived)
    }

    @Test
    fun `upcoming reminders exclude past, archived and deleted-category tasks`() = runTest {
        val now = Instant.parse("2026-10-06T12:00:00Z")
        val later = now.plusSeconds(3600)
        val active = db.insertCategory("active")
        val deleted = db.insertCategory("deleted", isDeleted = true)
        val wanted = db.insertTask(task(active, "wanted", reminderTime = later))
        db.insertTask(task(active, "past", reminderTime = now.minusSeconds(60)))
        db.insertTask(task(active, "archived", reminderTime = later, isArchived = true))
        db.insertTask(task(deleted, "deleted cat", reminderTime = later))

        assertEquals(
            listOf(TaskDao.IdTimeTuple(wanted, later)),
            dao.upcomingReminders(now)
        )
    }

    @Test
    fun `taskWithCatById joins the category and skips archived tasks`() = runTest {
        val cat = db.insertCategory("Errands", color = 0xFF112233)
        val live = db.insertTask(task(cat, "Post letter", due = today, recurrence = Recurrence.WEEKLY))
        val archived = db.insertTask(task(cat, "old", isArchived = true))

        val row = dao.taskWithCatById(live)!!
        assertEquals("Post letter", row.text)
        assertEquals("Errands", row.catTitle)
        assertEquals(0xFF112233, row.catColor)
        assertEquals(Recurrence.WEEKLY, row.recurrence)
        assertNull(dao.taskWithCatById(archived))
    }

    @Test
    fun `next-instance lookup matches category, text, recurrence and due`() = runTest {
        val cat = db.insertCategory()
        val next = today.plusDays(1)
        val id = db.insertTask(task(cat, "Water plants", due = next, recurrence = Recurrence.DAILY))

        assertEquals(id, dao.findNextInstanceId(cat, "Water plants", Recurrence.DAILY, next))
        assertNull(dao.findNextInstanceId(cat, "Water plants", Recurrence.WEEKLY, next))
        assertNull(dao.findNextInstanceId(cat, "Water plants", Recurrence.DAILY, today))
    }

    @Test
    fun `deleteNextInstance leaves completed instances alone`() = runTest {
        val cat = db.insertCategory()
        val next = today.plusDays(1)
        val open = db.insertTask(task(cat, "x", due = next, recurrence = Recurrence.DAILY))

        dao.deleteNextInstance(cat, "x", Recurrence.DAILY, next)
        assertNull(dao.taskById(open))

        val done = db.insertTask(
            task(cat, "x", due = next, recurrence = Recurrence.DAILY, status = TaskStatus.DONE, completedDate = today)
        )
        dao.deleteNextInstance(cat, "x", Recurrence.DAILY, next)
        assertEquals(done, dao.taskById(done)?.id)
    }
}
