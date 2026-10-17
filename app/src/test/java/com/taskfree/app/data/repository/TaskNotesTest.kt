package com.taskfree.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskfree.app.data.database.AppDatabase
import com.taskfree.app.data.entities.TASK_NOTE_LIMIT
import com.taskfree.app.data.entities.TASK_TITLE_LIMIT
import com.taskfree.app.domain.model.Recurrence
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

    @Test fun `unexpected insert errors are not treated as deleted parents`() = runTest {
        val id = db.insertTask(task(db.insertCategory()))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_note BEFORE INSERT ON TaskNote BEGIN SELECT RAISE(ABORT, 'injected'); END")
        assertFails<Exception> { repo.addNote(id, "Keep me") }
        assertTrue(db.taskNoteDao().getAllNow().isEmpty())
    }
}
