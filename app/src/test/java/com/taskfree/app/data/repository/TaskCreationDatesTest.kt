package com.taskfree.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskfree.app.data.database.AppDatabase
import com.taskfree.app.domain.model.Recurrence
import com.taskfree.app.domain.model.TaskInput
import com.taskfree.app.domain.model.TaskStatus
import com.taskfree.app.testutil.MutableClock
import com.taskfree.app.testutil.inMemoryDb
import com.taskfree.app.testutil.insertCategory
import com.taskfree.app.testutil.insertTask
import com.taskfree.app.testutil.task
import com.taskfree.app.util.DateProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class TaskCreationDatesTest {
    private val createdAt = Instant.parse("2026-10-06T08:15:30.123Z")
    private val today = LocalDate.of(2026, 10, 6)
    private lateinit var clock: MutableClock
    private lateinit var db: AppDatabase
    private lateinit var repo: TaskRepository

    @Before
    fun setUp() {
        clock = MutableClock(createdAt)
        db = inMemoryDb()
        repo = TaskRepository(db, DateProvider(clock))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `new tasks capture one timestamp at database precision`() = runTest {
        clock.advance(Duration.ofNanos(456789))
        val categoryId = db.insertCategory()

        for (recurrence in listOf(Recurrence.NONE, Recurrence.DAILY)) {
            val id = repo.createTask(TaskInput("Water plants", today, recurrence, categoryId))
            assertDates(id, createdAt, createdAt)
        }
    }

    @Test
    fun `completion and single archive carry the original date through generations`() = runTest {
        val id = repo.createTask(TaskInput("Water plants", today, Recurrence.DAILY, db.insertCategory()))

        clock.advance(Duration.ofDays(1))
        val secondId = repo.updateTaskStatus(id, TaskStatus.DONE).nextCreatedId!!
        val secondCreatedAt = clock.instant()
        assertDates(id, createdAt, createdAt)
        assertDates(secondId, createdAt, secondCreatedAt)

        clock.advance(Duration.ofDays(1))
        val thirdId = repo.archiveSingleOccurrence(repo.taskById(secondId)!!)!!
        assertDates(secondId, createdAt, secondCreatedAt)
        assertDates(thirdId, createdAt, clock.instant())

        repo.deleteTaskPermanently(repo.taskById(id)!!)
        repo.deleteTaskPermanently(repo.taskById(secondId)!!)
        assertDates(thirdId, createdAt, clock.instant())
    }

    @Test
    fun `legacy recurring tasks keep unknown original dates in both generation paths`() = runTest {
        val id = db.insertTask(task(db.insertCategory(), due = today, recurrence = Recurrence.DAILY))

        val secondId = repo.archiveSingleOccurrence(repo.taskById(id)!!)!!
        assertDates(id, null, null)
        assertDates(secondId, null, createdAt)

        clock.advance(Duration.ofDays(1))
        val thirdId = repo.updateTaskStatus(secondId, TaskStatus.DONE).nextCreatedId!!
        assertDates(thirdId, null, clock.instant())
    }

    @Test
    fun `undo then complete again timestamps only the recreated occurrence`() = runTest {
        val id = repo.createTask(TaskInput("Stretch", today, Recurrence.DAILY, db.insertCategory()))
        clock.advance(Duration.ofMinutes(10))
        val nextId = repo.updateTaskStatus(id, TaskStatus.DONE).nextCreatedId!!
        assertDates(nextId, createdAt, clock.instant())

        repo.updateTaskStatus(id, TaskStatus.TODO)
        assertNull(repo.taskById(nextId))
        assertDates(id, createdAt, createdAt)

        clock.advance(Duration.ofMinutes(10))
        val recreatedId = repo.updateTaskStatus(id, TaskStatus.DONE).nextCreatedId!!
        assertDates(recreatedId, createdAt, clock.instant())
        assertDates(id, createdAt, createdAt)
    }

    @Test
    fun `reusing an existing next occurrence preserves its timestamps`() = runTest {
        val id = repo.createTask(TaskInput("Stretch", today, Recurrence.DAILY, db.insertCategory()))
        val original = repo.taskById(id)!!
        clock.advance(Duration.ofMinutes(10))
        val nextId = repo.updateTaskStatus(id, TaskStatus.DONE).nextCreatedId!!
        val nextCreatedAt = clock.instant()

        clock.advance(Duration.ofMinutes(10))
        assertNull(repo.updateTaskStatus(id, TaskStatus.DONE).nextCreatedId)
        assertNull(repo.archiveSingleOccurrence(original))
        assertDates(nextId, createdAt, nextCreatedAt)
    }

    @Test
    fun `edits recurrence changes ordering and lifecycle actions preserve both dates`() = runTest {
        val categoryId = db.insertCategory()
        val id = repo.createTask(TaskInput("Stretch", today, Recurrence.DAILY, categoryId))
        clock.advance(Duration.ofDays(1))
        val nextId = repo.updateTaskStatus(id, TaskStatus.DONE).nextCreatedId!!
        val occurrenceCreatedAt = clock.instant()
        val otherCategory = db.insertCategory("Other")
        clock.advance(Duration.ofDays(1))

        for (recurrence in listOf(Recurrence.WEEKLY, Recurrence.NONE, Recurrence.MONTHLY)) {
            repo.updateTaskDetails(
                repo.taskById(nextId)!!, "New title", today.plusDays(30), recurrence,
                otherCategory, clock.instant().plusSeconds(3600)
            )
            assertDates(nextId, createdAt, occurrenceCreatedAt)
        }
        repo.updateTaskOrder(listOf(repo.taskById(nextId)!!.copy(allCategoryPageOrder = 9)))
        repo.reindexAllTaskPageOrders()
        repo.archiveTask(repo.taskById(nextId)!!)
        assertDates(nextId, createdAt, occurrenceCreatedAt)
        repo.unarchiveTask(nextId)
        repo.updateTaskStatus(nextId, TaskStatus.DONE)
        repo.updateTaskStatus(nextId, TaskStatus.IN_PROGRESS)
        assertDates(nextId, createdAt, occurrenceCreatedAt)
    }

    private suspend fun assertDates(id: Int, original: Instant?, occurrence: Instant?) {
        val saved = repo.taskById(id)!!
        assertEquals(original, saved.originalCreatedAt)
        assertEquals(occurrence, saved.occurrenceCreatedAt)
    }
}
