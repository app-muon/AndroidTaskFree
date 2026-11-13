package com.taskfree.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskfree.app.data.database.AppDatabase
import com.taskfree.app.data.entities.TASK_NOTE_LIMIT
import com.taskfree.app.data.entities.TASK_TITLE_LIMIT
import com.taskfree.app.data.entities.TaskNote
import com.taskfree.app.domain.model.Recurrence
import com.taskfree.app.domain.model.TaskInput
import com.taskfree.app.domain.model.TaskStatus
import com.taskfree.app.testutil.*
import com.taskfree.app.util.DateProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class TaskNotesTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: TaskRepository
    private val today = LocalDate.of(2026, 10, 16)
    private val clock = clockAt(today)

    @Before fun prepare() { db = inMemoryDb(); repo = TaskRepository(db, DateProvider(clock)) }
    @After fun close() = db.close()

    @Test fun `notes validate boundaries and preserve internal line breaks`() = runTest {
        val id = db.insertTask(task(db.insertCategory()))
        for (text in listOf("", " \n ", "x".repeat(TASK_NOTE_LIMIT + 1))) {
            assertFails<IllegalArgumentException> { repo.addNote(id, text) }
        }
        val note = repo.addNote(id, "  first\n\nsecond  ")!!
        assertEquals("first\n\nsecond", note.text)
        assertEquals(clock.instant(), note.createdAt)
        assertNull(note.editedAt)
        assertEquals(TASK_NOTE_LIMIT, repo.addNote(id, "x".repeat(TASK_NOTE_LIMIT))!!.text.length)
        for (text in listOf(" ", "x".repeat(TASK_NOTE_LIMIT + 1))) {
            assertFails<IllegalArgumentException> { repo.editNote(note.id, text) }
        }
        assertEquals(note, db.taskNoteDao().byId(note.id))
    }

    @Test fun `editing preserves creation and ordering and ignores unchanged text`() = runTest {
        val id = db.insertTask(task(db.insertCategory()))
        val first = repo.addNote(id, "First")!!
        val second = repo.addNote(id, "Second")!!
        assertEquals(listOf(second, first), repo.observeNotes(id).first())
        clock.advance(Duration.ofMinutes(1))
        assertEquals(first, repo.editNote(first.id, " First \n"))
        val edited = repo.editNote(first.id, "Changed\nline")!!
        assertEquals(first.createdAt, edited.createdAt)
        assertEquals(clock.instant(), edited.editedAt)
        assertEquals(listOf(second.id, first.id), repo.observeNotes(id).first().map { it.id })
        assertEquals(edited, repo.editNote(first.id, "Changed\nline "))
    }

    @Test fun `counts and content reflect note-only writes and deleted notes cannot return`() = runTest {
        val id = db.insertTask(task(db.insertCategory()))
        assertTrue(repo.observeNoteCounts().first().isEmpty())
        val note = repo.addNote(id, "Original")!!
        assertEquals(1, repo.observeNoteCounts().first().single().count)
        repo.editNote(note.id, "Edited")
        assertEquals("Edited", repo.observeNotes(id).first().single().text)
        repo.deleteNote(note.id)
        assertNull(repo.editNote(note.id, "Delayed edit"))
        assertTrue(repo.observeNotes(id).first().isEmpty())
        assertTrue(repo.observeNoteCounts().first().isEmpty())
        repo.deleteTaskPermanently(repo.taskById(id)!!)
        assertNull(repo.addNote(id, "Late insertion"))
    }

    @Test fun `title-only validation leaves every other column alone`() = runTest {
        val original = task(db.insertCategory(), "Original", due = today, recurrence = Recurrence.DAILY,
            reminderTime = clock.instant(), singleOrder = 7, allOrder = 4)
        val id = db.insertTask(original)
        assertTrue(repo.updateTitle(id, " New title "))
        assertEquals(original.copy(id = id, text = "New title"), repo.taskById(id))
        assertTrue(repo.updateTitle(id, "x".repeat(TASK_TITLE_LIMIT)))
        for (text in listOf(" \n ", "x".repeat(TASK_TITLE_LIMIT + 1))) {
            assertFails<IllegalArgumentException> { repo.updateTitle(id, text) }
        }
        assertEquals("x".repeat(TASK_TITLE_LIMIT), repo.taskById(id)!!.text)
        assertTrue(repo.updateTitle(id, "Line one\r\nline two"))
        assertEquals("Line one line two", repo.taskById(id)!!.text)
        assertFalse(repo.updateTitle(id + 100, "Missing"))
    }

    @Test fun `reopening protects a successor with notes and completing reuses its link`() = runTest {
        val id = db.insertTask(task(db.insertCategory(), due = today, recurrence = Recurrence.DAILY))
        val sourceNote = repo.addNote(id, "Source")!!
        val next = repo.updateTaskStatus(id, TaskStatus.DONE).nextCreatedId!!
        assertTrue(repo.observeNotes(next).first().isEmpty())
        val nextNote = repo.addNote(next, "Next")!!
        assertNull(repo.updateTaskStatus(id, TaskStatus.TODO).nextDeletedId)
        assertEquals(id, repo.taskById(next)!!.sourceTaskId)
        assertEquals(nextNote, db.taskNoteDao().byId(nextNote.id))
        assertNull(repo.updateTaskStatus(id, TaskStatus.DONE).nextCreatedId)
        assertEquals(2, repo.snapshot().size)
        repo.deleteNote(nextNote.id)
        assertEquals(next, repo.updateTaskStatus(id, TaskStatus.TODO).nextDeletedId)
        assertEquals(sourceNote, db.taskNoteDao().byId(sourceNote.id))
    }

    @Test fun `done and archived tasks accept notes and task or category removal cascades`() = runTest {
        val category = db.insertCategory()
        val id = db.insertTask(task(category, status = TaskStatus.DONE, completedDate = today))
        val first = repo.addNote(id, "Done note")!!
        repo.archiveTask(repo.taskById(id)!!)
        val second = repo.addNote(id, "Archived note")!!
        assertEquals(listOf(second, first), repo.observeNotes(id).first())
        repo.deleteAllArchivedTasks()
        assertTrue(db.taskNoteDao().getAllNow().isEmpty())
        val other = db.insertTask(task(category))
        repo.addNote(other, "Category cascade")
        db.categoryDao().deleteAll()
        assertTrue(db.taskNoteDao().getAllNow().isEmpty())
    }

    @Test fun `archiving recurring task retains its notes and creates an empty successor`() = runTest {
        val id = db.insertTask(task(db.insertCategory(), due = today, recurrence = Recurrence.DAILY))
        val note = repo.addNote(id, "Source")!!
        val next = repo.archiveSingleOccurrence(repo.taskById(id)!!)!!
        assertEquals(listOf(note), repo.observeNotes(id).first())
        assertTrue(repo.observeNotes(next).first().isEmpty())
    }

    @Test fun `cloning zero one or multiple notes preserves content dates and displayed order`() = runTest {
        val category = db.insertCategory()
        val input = TaskInput("Copy", null, Recurrence.NONE, category)
        val earlier = clock.instant().minusSeconds(120)
        val later = clock.instant().minusSeconds(60)
        // Creation order deliberately differs from timestamp order, with a tie in the middle.
        val times = listOf(later, earlier, later, clock.instant())
        for (count in listOf(0, 1, 4)) {
            val sourceId = repo.createTask(input.copy(title = "Source $count"))
            db.taskNoteDao().insertAll(times.take(count).mapIndexed { index, time ->
                TaskNote(taskId = sourceId, text = "Note $index\n\nSecond line", createdAt = time,
                    editedAt = if (index % 2 == 0) clock.instant() else null)
            })
            val sourceNotes = repo.observeNotes(sourceId).first()

            val cloneId = repo.cloneTask(sourceId, input)!!
            val copiedNotes = repo.observeNotes(cloneId).first()

            assertEquals(count, copiedNotes.size)
            assertEquals(sourceNotes, repo.observeNotes(sourceId).first())
            assertEquals(sourceNotes, copiedNotes.zip(sourceNotes) { copy, source ->
                assertEquals(cloneId, copy.taskId)
                copy.copy(id = source.id, taskId = sourceId)
            })
            val ids = (sourceNotes + copiedNotes).map { it.id }
            assertEquals(count * 2, ids.toSet().size)
        }
    }

    @Test fun `cloned notes remain independent through edits note deletion and either task deletion`() = runTest {
        val category = db.insertCategory()
        val input = TaskInput("Copy", null, Recurrence.NONE, category)
        for (deleteSource in listOf(true, false)) {
            val sourceId = repo.createTask(input.copy(title = "Source"))
            val sourceNotes = listOf("First", "Second", "Third").map { repo.addNote(sourceId, it)!! }
            val cloneId = repo.cloneTask(sourceId, input)!!
            val copiedNotes = db.taskNoteDao().getForTaskNow(cloneId)

            repo.editNote(sourceNotes[0].id, "Edited source")
            assertEquals(copiedNotes[0], db.taskNoteDao().byId(copiedNotes[0].id))
            repo.editNote(copiedNotes[1].id, "Edited clone")
            assertEquals(sourceNotes[1], db.taskNoteDao().byId(sourceNotes[1].id))

            repo.deleteNote(sourceNotes[1].id)
            assertEquals("Edited clone", db.taskNoteDao().byId(copiedNotes[1].id)!!.text)
            repo.deleteNote(copiedNotes[0].id)
            assertEquals("Edited source", db.taskNoteDao().byId(sourceNotes[0].id)!!.text)

            val deletedId = if (deleteSource) sourceId else cloneId
            val survivingId = if (deleteSource) cloneId else sourceId
            val survivingNotes = repo.observeNotes(survivingId).first()
            repo.deleteTaskPermanently(repo.taskById(deletedId)!!)
            assertTrue(repo.observeNotes(deletedId).first().isEmpty())
            assertNotNull(repo.taskById(survivingId))
            assertEquals(survivingNotes, repo.observeNotes(survivingId).first())
        }
    }

    @Test fun `cloning a deleted source creates nothing`() = runTest {
        val input = TaskInput("Source", null, Recurrence.NONE, db.insertCategory())
        val id = repo.createTask(input)
        repo.addNote(id, "Removed with source")
        repo.deleteTaskPermanently(repo.taskById(id)!!)

        assertNull(repo.cloneTask(id, input.copy(title = "Copy")))
        assertTrue(repo.snapshot().isEmpty())
        assertTrue(db.taskNoteDao().getAllNow().isEmpty())
    }

    @Test fun `failed second note insertion rolls back the cloned task and all copied notes`() = runTest {
        val input = TaskInput("Source", null, Recurrence.NONE, db.insertCategory())
        val id = repo.createTask(input)
        listOf("First", "Second", "Third").forEach { repo.addNote(id, it) }
        val tasksBefore = repo.snapshot()
        val notesBefore = db.taskNoteDao().getAllNow()
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER fail_copy BEFORE INSERT ON TaskNote
            WHEN NEW.taskId <> $id AND NEW.text = 'Second'
            BEGIN SELECT RAISE(ABORT, 'injected'); END
        """.trimIndent())

        assertFails<Exception> { repo.cloneTask(id, input.copy(title = "Copy")) }

        assertEquals(tasksBefore, repo.snapshot())
        assertEquals(notesBefore, db.taskNoteDao().getAllNow())
    }

    @Test fun `unexpected insert errors are not treated as deleted parents`() = runTest {
        val id = db.insertTask(task(db.insertCategory()))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_note BEFORE INSERT ON TaskNote BEGIN SELECT RAISE(ABORT, 'injected'); END")
        assertFails<Exception> { repo.addNote(id, "Keep me") }
        assertTrue(db.taskNoteDao().getAllNow().isEmpty())
    }
}
