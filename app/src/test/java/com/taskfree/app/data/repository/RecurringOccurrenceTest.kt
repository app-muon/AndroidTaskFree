package com.taskfree.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskfree.app.data.database.AppDatabase
import com.taskfree.app.domain.model.Recurrence
import com.taskfree.app.domain.model.TaskInput
import com.taskfree.app.domain.model.TaskStatus
import com.taskfree.app.testutil.MutableClock
import com.taskfree.app.testutil.clockAt
import com.taskfree.app.testutil.inMemoryDb
import com.taskfree.app.testutil.insertCategory
import com.taskfree.app.testutil.insertTask
import com.taskfree.app.testutil.task
import com.taskfree.app.util.DateProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class RecurringOccurrenceTest {
    private val dayOne = LocalDate.of(2026, 10, 6)
    private lateinit var db: AppDatabase
    private lateinit var clock: MutableClock
    private lateinit var repo: TaskRepository

    @Before
    fun setUp() {
        db = inMemoryDb()
        clock = clockAt(dayOne)
        repo = TaskRepository(db, DateProvider(clock))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun daily() = repo.createTask(
        TaskInput("Water plants", dayOne, Recurrence.DAILY, db.insertCategory())
    )

    @Test
    fun `undo on a later day deletes the exact successor and redo creates only one`() = runTest {
        val id = daily()
        val nextId = repo.updateTaskStatus(id, TaskStatus.DONE).nextCreatedId!!
        val originalDate = repo.taskById(id)!!.originalCreatedAt
        clock.advance(Duration.ofDays(1))

        assertEquals(nextId, repo.updateTaskStatus(id, TaskStatus.TODO).nextDeletedId)
        assertNull(repo.taskById(nextId))
        val recreatedId = repo.updateTaskStatus(id, TaskStatus.DONE).nextCreatedId!!
        val next = repo.taskById(recreatedId)!!
        assertEquals(dayOne.plusDays(2), next.due)
        assertEquals(id, next.sourceTaskId)
        assertEquals(originalDate, next.originalCreatedAt)
        assertEquals(2, repo.snapshot().size)
    }

    @Test
    fun `undo deletes an edited TODO successor even when title category due and recurrence changed`() = runTest {
        val id = daily()
        val nextId = repo.updateTaskStatus(id, TaskStatus.DONE).nextCreatedId!!
        val otherCategory = db.insertCategory("Other")
        repo.updateTaskDetails(repo.taskById(id)!!, "Edited", null, Recurrence.NONE, otherCategory, null)
        repo.updateTaskDetails(repo.taskById(nextId)!!, "Next edited", dayOne.plusDays(9), Recurrence.WEEKLY, otherCategory, null)
        clock.advance(Duration.ofDays(3))

        assertEquals(nextId, repo.updateTaskStatus(id, TaskStatus.TODO).nextDeletedId)
        assertEquals(listOf(id), repo.snapshot().map { it.id })
    }

    @Test
    fun `started and pending successors retain edits and links and prevent duplicates`() = runTest {
        for (status in listOf(TaskStatus.IN_PROGRESS, TaskStatus.PENDING)) {
            val id = daily()
            val nextId = repo.updateTaskStatus(id, TaskStatus.DONE).nextCreatedId!!
            val otherCategory = db.insertCategory("Other")
            repo.updateTaskDetails(
                repo.taskById(nextId)!!, "Edited successor", dayOne.plusDays(9),
                Recurrence.WEEKLY, otherCategory, clock.instant().plusSeconds(3600)
            )
            repo.updateTaskStatus(nextId, status)
            val saved = repo.taskById(nextId)!!
            val count = repo.snapshot().size

            assertEquals(id, saved.sourceTaskId)
            assertNull(repo.updateTaskStatus(id, TaskStatus.TODO).nextDeletedId)
            assertEquals(saved, repo.taskById(nextId))
            assertNull(repo.updateTaskStatus(id, TaskStatus.DONE).nextCreatedId)
            assertEquals(saved, repo.taskById(nextId))
            assertEquals(count, repo.snapshot().size)
        }
    }

    @Test
    fun `a successor returned to TODO is eligible for deletion again`() = runTest {
        val id = daily()
        val nextId = repo.updateTaskStatus(id, TaskStatus.DONE).nextCreatedId!!
        repo.updateTaskStatus(nextId, TaskStatus.IN_PROGRESS)
        repo.updateTaskStatus(nextId, TaskStatus.PENDING)
        repo.updateTaskStatus(nextId, TaskStatus.TODO)

        assertEquals(nextId, repo.updateTaskStatus(id, TaskStatus.TODO).nextDeletedId)
        assertNull(repo.taskById(nextId))
    }

    @Test
    fun `completed successors and their descendants survive undo and block duplicate generation`() = runTest {
        val id = daily()
        val nextId = repo.updateTaskStatus(id, TaskStatus.DONE).nextCreatedId!!
        val thirdId = repo.updateTaskStatus(nextId, TaskStatus.DONE).nextCreatedId!!

        assertNull(repo.updateTaskStatus(id, TaskStatus.TODO).nextDeletedId)
        assertNotNull(repo.taskById(nextId))
        assertNotNull(repo.taskById(thirdId))
        assertNull(repo.updateTaskStatus(id, TaskStatus.DONE).nextCreatedId)
        assertEquals(3, repo.snapshot().size)
    }

    @Test
    fun `archived successors are preserved and unrelated matching tasks are never deleted`() = runTest {
        val id = daily()
        val nextId = repo.updateTaskStatus(id, TaskStatus.DONE).nextCreatedId!!
        val next = repo.taskById(nextId)!!
        val manualId = db.insertTask(next.copy(id = 0, sourceTaskId = null))
        repo.archiveTask(next)

        assertNull(repo.updateTaskStatus(id, TaskStatus.TODO).nextDeletedId)
        assertTrue(repo.taskById(nextId)!!.isArchived)
        assertNotNull(repo.taskById(manualId))
        assertNull(repo.updateTaskStatus(id, TaskStatus.DONE).nextCreatedId)
    }

    @Test
    fun `legacy completed tasks never adopt or delete an unlinked matching task`() = runTest {
        val category = db.insertCategory()
        val id = db.insertTask(task(category, due = dayOne, recurrence = Recurrence.DAILY,
            status = TaskStatus.DONE, completedDate = dayOne))
        val nextId = db.insertTask(task(category, due = dayOne.plusDays(1), recurrence = Recurrence.DAILY))
        clock.advance(Duration.ofDays(1))
        assertNull(repo.updateTaskStatus(id, TaskStatus.TODO).nextDeletedId)
        assertNotNull(repo.taskById(nextId))
    }

    @Test
    fun `archiving a completed legacy occurrence does not duplicate its unlinked successor`() = runTest {
        val category = db.insertCategory()
        val id = db.insertTask(task(category, due = dayOne, recurrence = Recurrence.DAILY,
            status = TaskStatus.DONE, completedDate = dayOne))
        val nextId = db.insertTask(task(category, due = dayOne.plusDays(1), recurrence = Recurrence.DAILY))
        val completed = repo.taskById(id)!!
        val next = repo.taskById(nextId)!!
        clock.advance(Duration.ofDays(1))

        assertNull(repo.archiveSingleOccurrence(completed))

        assertEquals(completed.copy(isArchived = true), repo.taskById(id))
        assertEquals(next, repo.taskById(nextId))
        assertEquals(2, repo.snapshot().size)
    }

    @Test
    fun `archiving a completed occurrence does not recreate an intentionally deleted successor`() = runTest {
        val id = daily()
        val stale = repo.taskById(id)!!
        val nextId = repo.updateTaskStatus(id, TaskStatus.DONE).nextCreatedId!!
        val completed = repo.taskById(id)!!
        repo.deleteTaskPermanently(repo.taskById(nextId)!!)

        assertNull(repo.archiveSingleOccurrence(stale))

        assertEquals(listOf(completed.copy(isArchived = true)), repo.snapshot())
    }

    @Test
    fun `reopening and completing an archived occurrence preserves its successor`() = runTest {
        val id = daily()
        val nextId = repo.archiveSingleOccurrence(repo.taskById(id)!!)!!
        val next = repo.taskById(nextId)!!

        for (status in TaskStatus.entries.filter { it != TaskStatus.DONE }) {
            assertEquals(UpdateResult(), repo.updateTaskStatus(id, status))
            assertEquals(next, repo.taskById(nextId))
            assertEquals(status, repo.taskById(id)!!.status)
            assertNull(repo.taskById(id)!!.completedDate)

            assertEquals(UpdateResult(), repo.updateTaskStatus(id, TaskStatus.DONE))
            assertEquals(next, repo.taskById(nextId))
            assertTrue(repo.taskById(id)!!.isArchived)
            assertEquals(dayOne, repo.taskById(id)!!.completedDate)
            assertEquals(2, repo.snapshot().size)
        }
    }

    @Test
    fun `archive uses saved edits instead of the panel snapshot and generates from them`() = runTest {
        val id = daily()
        val snapshot = repo.taskById(id)!!
        val otherCategory = db.insertCategory("Other")
        val newDue = dayOne.plusDays(4)
        repo.updateTaskDetails(snapshot, "Edited", newDue, Recurrence.WEEKLY, otherCategory, null)
        val edited = repo.taskById(id)!!

        val nextId = repo.archiveSingleOccurrence(snapshot)!!
        assertEquals(edited.copy(isArchived = true, status = TaskStatus.DONE, completedDate = dayOne), repo.taskById(id))
        val next = repo.taskById(nextId)!!
        assertEquals("Edited", next.text)
        assertEquals(newDue.plusWeeks(1), next.due)
        assertEquals(otherCategory, next.categoryId)
    }

    @Test
    fun `series archive and unarchive preserve saved edits`() = runTest {
        val id = daily()
        val snapshot = repo.taskById(id)!!
        repo.updateTaskDetails(snapshot, "Edited", null, Recurrence.NONE, snapshot.categoryId, null)
        val edited = repo.taskById(id)!!
        repo.archiveTask(snapshot)
        assertEquals(edited.copy(isArchived = true), repo.taskById(id))
        repo.unarchiveTask(id)
        assertEquals(edited, repo.taskById(id))
    }
}
