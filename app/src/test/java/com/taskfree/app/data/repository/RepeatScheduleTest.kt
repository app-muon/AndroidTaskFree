package com.taskfree.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskfree.app.data.database.AppDatabase
import com.taskfree.app.data.entities.Task
import com.taskfree.app.data.entities.repeatRule
import com.taskfree.app.domain.model.*
import com.taskfree.app.testutil.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class RepeatScheduleTest {
    private val today = LocalDate.of(2026, 10, 6)
    private lateinit var db: AppDatabase
    private lateinit var repo: TaskRepository
    @Before fun setup() { db = inMemoryDb(); repo = TaskRepository(db, datesAt(today)) }
    @After fun close() = db.close()
    private fun Task.input(rule: RepeatRule = repeatRule, due: LocalDate? = this.due) =
        TaskInput(text, due, rule, categoryId, reminderTime)
    private suspend fun create(rule: RepeatRule = RepeatRule(Recurrence.NONE), due: LocalDate? = null) =
        repo.createTask(TaskInput("Task", due, rule, db.insertCategory()))

    @Test fun creationAndEnablingRequireExplicitDatesWithoutInventingReminder() = runTest {
        val rule = RepeatRule(Recurrence.WEEKLY, 3)
        val count = repo.snapshot().size
        assertEquals(TaskEditFailureReason.VALIDATION, assertFails<TaskEditException> {
            create(rule)
        }.reason)
        assertEquals(count, repo.snapshot().size)
        val created = repo.taskById(create(rule, today))!!
        assertEquals(today, created.due)
        assertEquals(today, created.baseDate)
        assertNull(created.reminderTime)
        val id = create()
        val before = repo.taskById(id)
        assertEquals(TaskEditFailureReason.VALIDATION, assertFails<TaskEditException> {
            repo.updateTaskDetails(id, true) { it.input(rule) }
        }.reason)
        assertEquals(before, repo.taskById(id))
        val saved = repo.updateTaskDetails(id, true) { it.input(rule, today) }
        assertEquals(rule, saved.repeatRule)
        assertEquals(today, saved.due)
        assertEquals(today, saved.baseDate)
        assertNull(saved.reminderTime)
        val invalidId = create()
        assertFails<IllegalArgumentException> {
            repo.updateTaskDetails(invalidId, true) { it.input(RepeatRule(Recurrence.WEEKENDS)) }
        }
        assertNull(repo.taskById(invalidId)!!.due)
    }

    @Test fun atomicScheduleEditsUseLatestTaskAndKeepUnrelatedFields() = runTest {
        val id = create(RepeatRule(Recurrence.DAILY, 2), today)
        val reminder = today.atTime(9, 30).atZone(ZoneId.systemDefault()).toInstant()
        db.taskDao().update(repo.taskById(id)!!.copy(reminderTime = reminder))
        repo.updateTitle(id, "Latest title")
        repo.updateTaskStatus(id, TaskStatus.IN_PROGRESS)
        val before = repo.taskById(id)!!
        val saved = repo.updateTaskDetails(id, true) { it.input(RepeatRule(Recurrence.MONTHLY, 2), today.plusDays(5)) }
        assertEquals(before.copy(
            due = today.plusDays(5), baseDate = today.plusDays(5),
            recurrence = Recurrence.MONTHLY, recurrenceInterval = 2,
            reminderTime = today.plusDays(5).atTime(9, 30).atZone(ZoneId.systemDefault()).toInstant()
        ), saved)
        assertEquals(saved, repo.taskById(id))
        val ruleOnly = repo.updateTaskDetails(id, true) { it.input(RepeatRule(Recurrence.WEEKLY, 99)) }
        assertEquals(saved.due, ruleOnly.due)
        assertEquals(saved.reminderTime, ruleOnly.reminderTime)
        val disabled = repo.updateTaskDetails(id, true) { it.input(RepeatRule(Recurrence.NONE)) }
        assertNull(disabled.baseDate)
        assertEquals(saved.due, disabled.due)
        assertEquals(saved.reminderTime, disabled.reminderTime)
    }

    @Test fun failedTransactionKeepsEveryFieldAndDatesAreValidatedForAllEdits() = runTest {
        val id = create(RepeatRule(Recurrence.WEEKDAYS), today)
        val before = repo.taskById(id)!!
        for (due in listOf(null, today.plusDays(4))) {
            assertFails<IllegalArgumentException> { repo.updateTaskDetails(id) { it.input(due = due) } }
            assertEquals(before, repo.taskById(id))
        }
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_repeat BEFORE UPDATE ON Task BEGIN SELECT RAISE(ABORT, 'injected'); END")
        assertFails<Exception> { repo.updateTaskDetails(id, true) { it.input(RepeatRule(Recurrence.MONTHLY, 2)) } }
        assertEquals(before, repo.taskById(id))
    }

    @Test fun historicalNavigationFollowsOnlyExplicitLinksAndRejectsStaleTargets() = runTest {
        val rule = RepeatRule(Recurrence.WEEKLY, 3)
        val id = create(rule, today)
        val historical = repo.taskById(id)!!
        val next = repo.updateTaskStatus(id, TaskStatus.DONE).nextCreatedId!!
        assertEquals(next, repo.editableRepeatTask(id)!!.id)
        assertEquals(rule, repo.taskById(next)!!.repeatRule)
        assertEquals(today.plusWeeks(3), repo.taskById(next)!!.due)
        repo.updateTaskStatus(next, TaskStatus.IN_PROGRESS)
        repo.updateTaskStatus(id, TaskStatus.TODO) // older occurrence reopened, child protected
        assertEquals(next, repo.editableRepeatTask(id)!!.id)
        assertEquals(TaskEditFailureReason.STALE_REPEAT_TARGET, assertFails<TaskEditException> {
            repo.updateTaskDetails(id, true) { it.input(RepeatRule(Recurrence.DAILY, 2)) }
        }.reason)
        assertEquals(historical.repeatRule, repo.taskById(id)!!.repeatRule)
        repo.archiveTask(repo.taskById(next)!!)
        assertNull(repo.editableRepeatTask(id))
        val unlinked = create(rule, today)
        assertEquals(unlinked, repo.editableRepeatTask(unlinked)!!.id)
        repo.updateTaskStatus(unlinked, TaskStatus.DONE)
        val child = repo.editableRepeatTask(unlinked)!!
        repo.deleteTaskPermanently(child)
        val count = repo.snapshot().size
        assertNull(repo.editableRepeatTask(unlinked))
        assertEquals(count, repo.snapshot().size)
    }

    @Test fun archiveCloneUndoAndRestoreCarryIntervalsAndRetainSuccessorProtection() = runTest {
        val rule = RepeatRule(Recurrence.MONTHLY, 2)
        val id = create(rule, today)
        val source = repo.taskById(id)!!
        val clone = repo.cloneTask(id, source.input())!!
        assertEquals(rule, repo.taskById(clone)!!.repeatRule)
        assertNull(repo.taskById(clone)!!.sourceTaskId)
        val next = repo.archiveSingleOccurrence(source)!!
        assertEquals(rule, repo.taskById(next)!!.repeatRule)
        assertEquals(today.plusMonths(2), repo.taskById(next)!!.due)
        repo.unarchiveTask(id)
        // Existing undo deletes even an edited TODO child.
        repo.updateTaskDetails(next, true) { it.input(RepeatRule(Recurrence.WEEKLY, 99)) }
        assertEquals(next, repo.updateTaskStatus(id, TaskStatus.TODO).nextDeletedId)
        val recreated = repo.updateTaskStatus(id, TaskStatus.DONE).nextCreatedId!!
        assertEquals(rule, repo.taskById(recreated)!!.repeatRule)
        repo.updateTaskStatus(recreated, TaskStatus.PENDING)
        assertNull(repo.updateTaskStatus(id, TaskStatus.TODO).nextDeletedId)
        assertNull(repo.updateTaskStatus(id, TaskStatus.DONE).nextCreatedId)
        repo.archiveSeries(id)
        assertTrue(repo.taskById(recreated)!!.isArchived)
        repo.unarchiveTask(recreated)
        assertEquals(rule, repo.taskById(recreated)!!.repeatRule)
    }

    @Test fun archivedDeletedAndMissingTargetsCannotSaveRepeat() = runTest {
        val id = create()
        repo.archiveTask(repo.taskById(id)!!)
        assertNull(repo.editableRepeatTask(id))
        assertEquals(TaskEditFailureReason.STALE_REPEAT_TARGET, assertFails<TaskEditException> {
            repo.updateTaskDetails(id, true) { it.input(RepeatRule(Recurrence.DAILY, 2)) }
        }.reason)
        val active = create()
        val category = db.categoryDao().getAllNow().first { it.id == repo.taskById(active)!!.categoryId }
        db.categoryDao().markDeleted(category.id)
        assertNull(repo.editableRepeatTask(active))
        assertEquals(TaskEditFailureReason.STALE_REPEAT_TARGET, assertFails<TaskEditException> {
            repo.updateTaskDetails(active, true) { it.input(RepeatRule(Recurrence.DAILY, 2)) }
        }.reason)
        assertEquals(TaskEditFailureReason.MISSING_TASK, assertFails<TaskEditException> {
            repo.updateTaskDetails(999, true) { it.input() }
        }.reason)
    }

    @Test fun clonePreservesLegacyDatesButRejectsNewIncompatibleDates() = runTest {
        for ((recurrence, due) in listOf(
            Recurrence.WEEKDAYS to today.plusDays(4), // Saturday
            Recurrence.WEEKENDS to today // Tuesday
        )) {
            val source = Task(categoryId = db.insertCategory(), text = "Legacy", due = due,
                baseDate = due, recurrence = recurrence, singleCategoryPageOrder = 0)
            val id = db.taskDao().insert(source).toInt()
            val clone = repo.taskById(repo.cloneTask(id, source.input())!!)!!
            assertEquals(due, clone.due)
            assertEquals(source.repeatRule, clone.repeatRule)
            assertFails<IllegalArgumentException> {
                repo.cloneTask(id, source.input(due = due.plusWeeks(1)))
            }
            assertFails<IllegalArgumentException> { repo.createTask(source.input()) }
        }
    }

    @Test fun previewBaseMatchesSuccessorForLegacyRuleOnlyEditsAndChangedDates() = runTest {
        for (changeDate in listOf(false, true)) {
            val id = create(RepeatRule(Recurrence.DAILY), today)
            val legacy = repo.taskById(id)!!.copy(baseDate = today.minusDays(1))
            db.taskDao().update(legacy)
            val rule = RepeatRule(Recurrence.DAILY, 2)
            val due = if (changeDate) today.plusDays(3) else today
            val previewBase = rule.baseDateAfterEdit(due, legacy.recurrence, legacy.due, legacy.baseDate)
            val preview = rule.calculateNextValidDueDate(previewBase!!, today)
            val saved = repo.updateTaskDetails(id, true) { it.input(rule, due) }
            assertEquals(previewBase, saved.baseDate)
            val next = repo.updateTaskStatus(id, TaskStatus.DONE).nextCreatedId!!
            assertEquals(preview, repo.taskById(next)!!.due)
            assertEquals(if (changeDate) today.plusDays(5) else today.plusDays(1), preview)
        }
    }
}
