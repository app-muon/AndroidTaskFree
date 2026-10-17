package com.taskfree.app.ui.task

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskfree.app.data.database.AppDatabase
import com.taskfree.app.data.entities.Task
import com.taskfree.app.data.entities.TASK_NOTE_LIMIT
import com.taskfree.app.data.entities.TASK_TITLE_LIMIT
import com.taskfree.app.data.repository.TaskRepository
import com.taskfree.app.testutil.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class TaskSheetEditorTest {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var db: AppDatabase
    private lateinit var repo: TaskRepository
    private lateinit var task: Task
    private lateinit var editor: TaskSheetEditor
    private lateinit var queue: TaskMutationQueue

    @Before fun prepare() { db = inMemoryDb(); repo = TaskRepository(db, datesAt(LocalDate.of(2026, 10, 16))) }
    @After fun close() = db.close()

    private suspend fun TestScope.open(title: String = "Original") {
        val id = db.insertTask(task(db.insertCategory(), title))
        task = repo.taskById(id)!!
        queue = TaskMutationQueue(backgroundScope) { throw AssertionError(it) }
        editor = TaskSheetEditor(repo, backgroundScope, main.dispatcher, queue)
        editor.open(task)
    }

    private fun type(key: TaskTextField, text: String) {
        editor.focus(key)
        editor.edit(key, TextFieldValue(text, TextRange(text.length)))
    }
    private suspend fun settle() { queue.submit {}.join() }
    private fun notes() = db.taskNoteDao()

    @Test fun `drafts survive pauses and reattachment without a database write`() = runTest {
        open()
        type(TaskTextField.Composer, "Draft\nwith link https://example.com")
        advanceTimeBy(5_000)
        editor.open(task) // A recreated UI attaches to the same ViewModel state.
        type(TaskTextField.Title, "Original")
        settle()
        assertTrue(notes().getAllNow().isEmpty())
        assertEquals("Draft\nwith link https://example.com", editor.state.value!!.composer.text)
        assertEquals(TaskTextField.Title, editor.state.value!!.active)
    }

    @Test fun `Add close and background share one pending insertion`() = runTest {
        open()
        val gate = CompletableDeferred<Unit>()
        queue.submit { gate.await() }
        type(TaskTextField.Composer, "  Submit once  ")
        editor.addNote()
        editor.addNote()
        editor.open(task)
        editor.background()
        var closed = 0
        editor.close { closed++ }
        editor.close { closed++ }
        assertTrue(editor.state.value!!.submitting)
        assertEquals("  Submit once  ", editor.state.value!!.composer.text)
        gate.complete(Unit)
        settle()
        assertEquals(1, closed)
        assertNull(editor.state.value)
        assertEquals("Submit once", notes().getAllNow().single().text)
        assertNull(notes().getAllNow().single().editedAt)
        editor.open(task)
        assertEquals("", editor.state.value!!.composer.text)
    }

    @Test fun `background submission clears composer and later pencil edits mark edited`() = runTest {
        open()
        type(TaskTextField.Composer, "First draft")
        editor.background().join()
        val note = notes().getAllNow().single()
        assertNull(note.editedAt)
        assertEquals("", editor.state.value!!.composer.text)
        editor.state.first { it!!.notes.isNotEmpty() }
        type(TaskTextField.Note(note.id), "Changed")
        editor.flushEdits().join()
        assertNotNull(notes().byId(note.id)!!.editedAt)
        assertEquals(note.createdAt, notes().byId(note.id)!!.createdAt)
    }

    @Test fun `one second pause and subsequent close write normalized title only once`() = runTest {
        open()
        db.openHelper.writableDatabase.execSQL("CREATE TABLE TitleWrites (value TEXT)")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER count_title AFTER UPDATE OF text ON Task BEGIN INSERT INTO TitleWrites VALUES (NEW.text); END")
        type(TaskTextField.Title, "  Latest  ")
        advanceTimeBy(999)
        assertEquals("Original", runBlocking { repo.taskById(task.id)!!.text })
        advanceTimeBy(2)
        settle()
        assertEquals("Latest", repo.taskById(task.id)!!.text)
        assertEquals("  Latest  ", editor.state.value!!.title.value.text)
        editor.close {}.join()
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM TitleWrites").use {
            it.moveToFirst(); assertEquals(1, it.getInt(0))
        }
    }

    @Test fun `an older success preserves newer text and cursor until the later save`() = runTest {
        open()
        val gate = CompletableDeferred<Unit>()
        queue.submit { gate.await() }
        type(TaskTextField.Title, "Earlier")
        editor.flushEdits()
        type(TaskTextField.Title, "Latest")
        editor.prepareFlush() // Stop the timer so this assertion isolates the older acknowledgement.
        gate.complete(Unit)
        settle()
        assertEquals("Earlier", repo.taskById(task.id)!!.text)
        assertEquals(TextFieldValue("Latest", TextRange(6)), editor.state.value!!.title.value)
        editor.close {}.join()
        assertEquals("Latest", repo.taskById(task.id)!!.text)
    }

    @Test fun `blank exit restores a preceding successful write still in flight`() = runTest {
        open()
        val gate = CompletableDeferred<Unit>()
        queue.submit { gate.await() }
        type(TaskTextField.Title, "Saved shortly")
        editor.flushEdits()
        type(TaskTextField.Title, "  ")
        editor.blur(TaskTextField.Title)
        gate.complete(Unit)
        settle()
        assertEquals("Saved shortly", repo.taskById(task.id)!!.text)
        assertEquals("Saved shortly", editor.state.value!!.title.value.text)
    }

    @Test fun `closing flushes existing note and blank note exit never deletes it`() = runTest {
        open()
        val note = repo.addNote(task.id, "Existing")!!
        editor.state.first { it!!.notes.isNotEmpty() }
        type(TaskTextField.Note(note.id), "  Updated\nline  ")
        editor.focus(TaskTextField.Composer)
        settle()
        assertEquals("Updated\nline", notes().byId(note.id)!!.text)
        type(TaskTextField.Note(note.id), " \n ")
        editor.blur(TaskTextField.Note(note.id))
        settle()
        assertEquals("Updated\nline", editor.state.value!!.edits.getValue(note.id).value.text)
        type(TaskTextField.Note(note.id), "Final keystroke")
        editor.close {}.join()
        assertEquals("Final keystroke", notes().byId(note.id)!!.text)
    }

    @Test fun `failed insertion keeps close open and Retry inserts once`() = runTest {
        open()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_note BEFORE INSERT ON TaskNote BEGIN SELECT RAISE(ABORT, 'injected'); END")
        type(TaskTextField.Composer, "Retained")
        var closed = false
        editor.addNote()
        editor.close { closed = true }.join()
        assertFalse(closed)
        assertEquals("Retained", editor.state.value!!.composer.text)
        assertTrue(editor.state.value!!.submissionFailed)
        assertFalse(editor.state.value!!.submitting)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_note")
        editor.retry().join()
        assertTrue(closed)
        assertEquals("Retained", notes().getAllNow().single().text)
        assertNull(notes().getAllNow().single().editedAt)
    }

    @Test fun `background failure retains text on return and successful Retry clears composer`() = runTest {
        open()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_note BEFORE INSERT ON TaskNote BEGIN SELECT RAISE(ABORT, 'injected'); END")
        type(TaskTextField.Composer, "Retained")
        editor.background().join()
        editor.open(task)
        assertTrue(editor.state.value!!.hasError)
        assertEquals("Retained", editor.state.value!!.composer.text)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_note")
        editor.retry().join()
        assertFalse(editor.state.value!!.hasError)
        assertEquals("", editor.state.value!!.composer.text)
    }

    @Test fun `failed autosave preserves a newer edit and close retries the latest value`() = runTest {
        open()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_title BEFORE UPDATE OF text ON Task BEGIN SELECT RAISE(ABORT, 'injected'); END")
        type(TaskTextField.Title, "First")
        editor.flushEdits()
        type(TaskTextField.Title, "Newest")
        settle()
        assertTrue(editor.state.value!!.title.failed)
        assertEquals("Newest", editor.state.value!!.title.value.text)
        var closed = false
        editor.close { closed = true }.join()
        assertFalse(closed)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_title")
        editor.retry().join()
        assertTrue(closed)
        assertEquals("Newest", repo.taskById(task.id)!!.text)
    }

    @Test fun `input beyond limits is rejected whole and replacing or deleting remains possible`() = runTest {
        open()
        for ((key, limit) in listOf(TaskTextField.Title to TASK_TITLE_LIMIT, TaskTextField.Composer to TASK_NOTE_LIMIT)) {
            type(key, "x".repeat(limit))
            editor.edit(key, TextFieldValue("y".repeat(limit + 1)))
            val s = editor.state.value!!
            assertEquals("x".repeat(limit), if (key == TaskTextField.Title) s.title.value.text else s.composer.text)
            assertTrue(if (key == TaskTextField.Title) s.title.limitReached else s.composerLimitReached)
            editor.edit(key, TextFieldValue("Replaced"))
            editor.edit(key, TextFieldValue(""))
            assertEquals("", if (key == TaskTextField.Title) editor.state.value!!.title.value.text else editor.state.value!!.composer.text)
        }
    }

    @Test fun `queued update cannot resurrect a deliberately deleted note`() = runTest {
        open()
        val note = repo.addNote(task.id, "Existing")!!
        editor.state.first { it!!.notes.isNotEmpty() }
        type(TaskTextField.Note(note.id), "Delayed")
        editor.deleteNote(note.id)
        editor.flushEdits().join()
        assertTrue(notes().getAllNow().isEmpty())
        assertFalse(editor.state.value!!.hasError)
    }

    @Test fun `pending insertion for a deleted parent is abandoned`() = runTest {
        open()
        queue.submit { repo.deleteTaskPermanently(task) }
        type(TaskTextField.Composer, "Deleted parent")
        editor.addNote().join()
        assertTrue(notes().getAllNow().isEmpty())
        assertEquals("", editor.state.value!!.composer.text)
        assertFalse(editor.state.value!!.hasError)
    }

    @Test fun `failed deletion retains its note and Retry executes the deletion`() = runTest {
        open()
        val note = repo.addNote(task.id, "Keep until deleted")!!
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_delete BEFORE DELETE ON TaskNote BEGIN SELECT RAISE(ABORT, 'injected'); END")
        editor.deleteNote(note.id).join()
        assertTrue(editor.state.value!!.hasError)
        assertNotNull(notes().byId(note.id))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_delete")
        editor.retry().join()
        assertNull(notes().byId(note.id))
        assertFalse(editor.state.value!!.hasError)
    }

    @Test fun `Add retries a known insertion failure and repeated taps still submit once`() = runTest {
        open()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_note BEFORE INSERT ON TaskNote BEGIN SELECT RAISE(ABORT, 'injected'); END")
        type(TaskTextField.Composer, "Retry using Add")
        editor.addNote().join()
        assertTrue(editor.state.value!!.submissionFailed)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_note")
        editor.addNote()
        editor.addNote().join()
        assertEquals(1, notes().getAllNow().size)
        assertNull(notes().getAllNow().single().editedAt)
    }

    @Test fun `a successful Add closes the composer but a failed one keeps it open`() = runTest {
        open()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_note BEFORE INSERT ON TaskNote BEGIN SELECT RAISE(ABORT, 'injected'); END")
        type(TaskTextField.Composer, "Note")
        editor.addNote().join()
        assertEquals(TaskTextField.Composer, editor.state.value!!.active)
        assertEquals("Note", editor.state.value!!.composer.text)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_note")
        editor.addNote().join()
        assertNull(editor.state.value!!.active)
        assertEquals("", editor.state.value!!.composer.text)
    }

    @Test fun `a failed action shows the error without preventing close`() = runTest {
        open()
        val note = repo.addNote(task.id, "Kept")!!
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_delete BEFORE DELETE ON TaskNote BEGIN SELECT RAISE(ABORT, 'injected'); END")
        editor.deleteNote(note.id).join()
        assertTrue(editor.state.value!!.hasError)
        var closed = false
        editor.close { closed = true }.join()
        assertTrue(closed)
        assertNotNull(notes().byId(note.id))
    }

    @Test fun `Retry of a failed note edit leaves an unsubmitted draft in the composer`() = runTest {
        open()
        val note = repo.addNote(task.id, "Existing")!!
        editor.state.first { it!!.notes.isNotEmpty() }
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_edit BEFORE UPDATE ON TaskNote BEGIN SELECT RAISE(ABORT, 'injected'); END")
        type(TaskTextField.Note(note.id), "Edited")
        type(TaskTextField.Composer, "Unsent")
        settle()
        assertTrue(editor.state.value!!.edits.getValue(note.id).failed)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_edit")
        editor.retry().join()
        assertEquals("Edited", notes().byId(note.id)!!.text)
        assertEquals(1, notes().getAllNow().size)
        assertEquals("Unsent", editor.state.value!!.composer.text)
        assertFalse(editor.state.value!!.hasError)
    }

    @Test fun `a failed close lapses once its error is fixed by editing`() = runTest {
        open()
        val note = repo.addNote(task.id, "Existing")!!
        editor.state.first { it!!.notes.isNotEmpty() }
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_title BEFORE UPDATE OF text ON Task BEGIN SELECT RAISE(ABORT, 'injected'); END")
        type(TaskTextField.Title, "Unsaved")
        var closed = false
        editor.close { closed = true }.join()
        assertFalse(closed)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_title")
        type(TaskTextField.Title, "Saved")
        advanceTimeBy(1_001)
        settle()
        assertFalse(editor.state.value!!.hasError)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_edit BEFORE UPDATE ON TaskNote BEGIN SELECT RAISE(ABORT, 'injected'); END")
        type(TaskTextField.Note(note.id), "Edited")
        editor.flushEdits().join()
        assertTrue(editor.state.value!!.hasError)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_edit")
        editor.retry().join()
        assertFalse(closed)
        assertEquals("Edited", notes().byId(note.id)!!.text)
        assertFalse(editor.state.value!!.hasError)
    }

    @Test fun `an action failing after the sheet closed is never replayed by Retry`() = runTest {
        open()
        editor.close {}.join()
        var replayed = false
        editor.actionFailed { replayed = true }
        editor.open(task)
        assertFalse(editor.state.value!!.hasError)
        editor.retry().join()
        assertFalse(replayed)
    }

    @Test fun `an overlong stored title can be shortened and is never saved while too long`() = runTest {
        val long = "x".repeat(TASK_TITLE_LIMIT + 50)
        open(long)
        editor.focus(TaskTextField.Title)
        editor.edit(TaskTextField.Title, TextFieldValue(long.drop(1), TextRange(long.length - 1)))
        assertEquals(long.drop(1), editor.state.value!!.title.value.text)
        assertTrue(editor.state.value!!.title.limitReached)
        editor.edit(TaskTextField.Title, TextFieldValue(long, TextRange(long.length)))
        assertEquals(long.drop(1), editor.state.value!!.title.value.text)
        advanceTimeBy(1_001)
        settle()
        assertFalse(editor.state.value!!.hasError)
        editor.blur(TaskTextField.Title)
        settle()
        assertEquals(long, editor.state.value!!.title.value.text)
        editor.focus(TaskTextField.Title)
        editor.edit(TaskTextField.Title, TextFieldValue(long.take(TASK_TITLE_LIMIT + 10)))
        var closed = false
        editor.close { closed = true }.join()
        assertTrue(closed)
        assertEquals(long, repo.taskById(task.id)!!.text)
        editor.open(task)
        type(TaskTextField.Title, "y".repeat(TASK_TITLE_LIMIT))
        editor.close {}.join()
        assertEquals("y".repeat(TASK_TITLE_LIMIT), repo.taskById(task.id)!!.text)
    }

    @Test fun `a line break pasted into the title is saved as a space`() = runTest {
        open()
        type(TaskTextField.Title, "Line one\nline two")
        assertEquals("Line one line two", editor.state.value!!.title.value.text)
        editor.close {}.join()
        assertEquals("Line one line two", repo.taskById(task.id)!!.text)
    }
}
