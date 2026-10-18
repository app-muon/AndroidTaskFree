package com.taskfree.app.ui.task

import androidx.compose.ui.text.input.TextFieldValue
import com.taskfree.app.data.entities.Task
import com.taskfree.app.data.entities.TaskNote
import com.taskfree.app.data.entities.TASK_NOTE_LIMIT
import com.taskfree.app.data.entities.TASK_TITLE_LIMIT
import com.taskfree.app.data.repository.TaskRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface TaskTextField {
    data object Title : TaskTextField
    data object Composer : TaskTextField
    data class Note(val id: Int) : TaskTextField
}

data class SavedText(
    val value: TextFieldValue,
    val saved: String,
    val revision: Long = 0,
    val failed: Boolean = false,
    val limitReached: Boolean = false
)

data class TaskSheetState(
    val taskId: Int,
    val title: SavedText,
    val notes: List<TaskNote> = emptyList(),
    val edits: Map<Int, SavedText> = emptyMap(),
    val active: TaskTextField? = null,
    val composer: TextFieldValue = TextFieldValue(),
    val composerLimitReached: Boolean = false,
    val submitting: Boolean = false,
    val submissionFailed: Boolean = false,
    val actionFailed: Boolean = false,
    val closing: Boolean = false
) {
    /** Unsaved text keeps the sheet open; a failed action only shows the error. */
    val hasTextError: Boolean get() = title.failed || edits.values.any { it.failed } || submissionFailed
    val hasError: Boolean get() = hasTextError || actionFailed
}

/** Owned by TaskViewModel, never by a composable. Only the current sheet retains drafts. */
internal class TaskSheetEditor(
    private val repo: TaskRepository,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val queue: TaskMutationQueue
) {
    private val mutableState = MutableStateFlow<TaskSheetState?>(null)
    val state = mutableState.asStateFlow()
    private var observation: Job? = null
    private var debounce: Job? = null
    private var session = 0L
    private var submission: Submission? = null
    private var retryClose: (() -> Unit)? = null
    private var retryAction: (() -> Unit)? = null
    /** The title as it was when editing it began, which Cancel puts back. */
    private var titleBeforeEdit: String? = null

    private class Submission(val text: String) {
        var attempted = false
        var succeeded = false
    }

    fun open(task: Task) {
        if (state.value?.taskId == task.id) return
        check(state.value == null) { "Flush and close the current sheet before opening another" }
        retryAction = null
        retryClose = null
        session++
        mutableState.value = TaskSheetState(task.id, SavedText(TextFieldValue(task.text), task.text))
        observation = scope.launch {
            repo.observeNotes(task.id).collect { notes ->
                change { it.copy(notes = notes) }
            }
        }
    }

    private fun change(update: (TaskSheetState) -> TaskSheetState) {
        val next = state.value?.let(update)
        mutableState.value = next
        // A pending close lasts only while the failure that stopped it remains.
        if (next == null || (!next.closing && !next.hasTextError)) retryClose = null
    }

    private fun limit(field: TaskTextField) =
        if (field == TaskTextField.Title) TASK_TITLE_LIMIT else TASK_NOTE_LIMIT

    /** The text a save would write, or null for blank or overlong text, which is never saved. */
    private fun savable(field: TaskTextField, text: String): String? =
        text.trim().takeIf { it.isNotBlank() && it.length <= limit(field) }

    private fun TaskSheetState.textFields() =
        listOf(TaskTextField.Title to title) + edits.map { TaskTextField.Note(it.key) to it.value }

    private fun field(field: TaskTextField): SavedText? = when (field) {
        TaskTextField.Title -> state.value?.title
        is TaskTextField.Note -> state.value?.edits?.get(field.id)
        TaskTextField.Composer -> null
    }

    private fun changeField(field: TaskTextField, update: (SavedText) -> SavedText) = change { s ->
        when (field) {
            TaskTextField.Title -> s.copy(title = update(s.title))
            is TaskTextField.Note -> s.edits[field.id]?.let {
                s.copy(edits = s.edits + (field.id to update(it)))
            } ?: s
            TaskTextField.Composer -> s
        }
    }

    fun focus(field: TaskTextField?) {
        val s = state.value ?: return
        if (s.active == field) return
        flushEdits()
        if (field == TaskTextField.Title) titleBeforeEdit = s.title.value.text
        change {
            val note = (field as? TaskTextField.Note)?.let { key ->
                it.notes.firstOrNull { n -> n.id == key.id }
            }
            it.copy(active = field, edits = if (note != null && note.id !in it.edits)
                it.edits + (note.id to SavedText(TextFieldValue(note.text), note.text)) else it.edits)
        }
    }

    fun blur(field: TaskTextField) {
        if (state.value?.active == field) focus(null)
    }

    /** Stops editing the title and puts back its earlier text, saving that again if a pause already saved the edit. */
    fun cancelTitle(): Job {
        val s = state.value ?: return queue.submit {}
        if (s.active != TaskTextField.Title) return queue.submit {}
        val before = titleBeforeEdit ?: s.title.saved
        change {
            it.copy(active = null, title = it.title.copy(value = TextFieldValue(before),
                revision = it.title.revision + 1, limitReached = false))
        }
        return flushEdits()
    }

    fun edit(field: TaskTextField, value: TextFieldValue) {
        val s = state.value ?: return
        if (field == TaskTextField.Composer) {
            if (s.submitting) return
            if (value.text.length > TASK_NOTE_LIMIT) {
                change { it.copy(composerLimitReached = true) }
            } else {
                // A known failed insertion has not committed. A revised draft gets a new attempt.
                submission = null
                change { it.copy(composer = value, composerLimitReached = false, submissionFailed = false) }
            }
            return
        }
        val previous = field(field) ?: return
        // A title is one line. Each pasted line break becomes a space, keeping selection offsets valid.
        val input = if (field == TaskTextField.Title)
            value.copy(text = value.text.replace('\r', ' ').replace('\n', ' ')) else value
        val limit = limit(field)
        // Reject growth past the limit, but let an overlong stored value be shortened.
        if (input.text.length > limit && input.text.length > previous.value.text.length) {
            changeField(field) { it.copy(limitReached = true) }
            return
        }
        changeField(field) {
            it.copy(value = input, revision = it.revision + 1, limitReached = input.text.length > limit)
        }
        if (input.text != previous.value.text) {
            debounce?.cancel()
            debounce = scope.launch {
                delay(1_000)
                val save = prepareFlush(exit = false)
                queue.submit { save() }
            }
        }
    }

    fun flushEdits(): Job {
        val save = prepareFlush(exit = true)
        return queue.submit { save() }
    }

    /** Capture text synchronously, before enqueuing any dependent task mutation. */
    fun prepareFlush(exit: Boolean = true): suspend () -> Boolean {
        debounce?.cancel()
        val s = state.value ?: return { true }
        val token = session
        val fields = s.textFields()
        return save@{
            if (token != session) return@save true
            var titleSaved = true
            for ((key, draft) in fields) {
                val ok = saveField(key, draft, exit)
                if (key == TaskTextField.Title) titleSaved = ok
            }
            titleSaved
        }
    }

    private suspend fun saveField(key: TaskTextField, draft: SavedText, exit: Boolean): Boolean {
        val current = field(key) ?: return true // Deliberately deleted note.
        val normalized = savable(key, draft.value.text)
        if (normalized == null) {
            // This runs after earlier writes have acknowledged their saved values.
            if (exit) changeField(key) {
                if (it.revision == draft.revision)
                    it.copy(value = TextFieldValue(it.saved), failed = false, limitReached = false) else it
            }
            return true
        }
        if (normalized == current.saved) {
            acknowledge(key, draft, normalized, exit)
            return true
        }
        return try {
            val exists = withContext(io) {
                when (key) {
                    TaskTextField.Title -> repo.updateTitle(state.value!!.taskId, normalized)
                    is TaskTextField.Note -> repo.editNote(key.id, normalized) != null
                    TaskTextField.Composer -> true
                }
            }
            if (!exists && key is TaskTextField.Note) {
                change { it.copy(edits = it.edits - key.id,
                    active = it.active.takeUnless { active -> active == key }) }
            } else acknowledge(key, draft, normalized, exit)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            changeField(key) { it.copy(failed = true) }
            false
        }
    }

    private fun acknowledge(key: TaskTextField, draft: SavedText, saved: String, exit: Boolean) {
        changeField(key) {
            it.copy(saved = saved, failed = false, value =
                if (it.revision == draft.revision && exit && state.value?.active != key)
                    TextFieldValue(saved) else it.value)
        }
    }

    private fun prepareSubmission(): Submission? {
        val s = state.value ?: return null
        submission?.let { return it }
        if (s.composer.text.isBlank()) return null
        return Submission(s.composer.text).also {
            submission = it
            change { current -> current.copy(submitting = true, submissionFailed = false) }
        }
    }

    private suspend fun submit(attempt: Submission?): Boolean {
        if (attempt == null) return true
        if (attempt.attempted) return attempt.succeeded
        attempt.attempted = true
        return try {
            withContext(io) { repo.addNote(state.value!!.taskId, attempt.text) }
            // A removed parent is an expected missing target; abandon its pending insertion.
            attempt.succeeded = true
            if (submission === attempt) {
                submission = null
                // A saved note returns the section to its "Add a note" link.
                change { it.copy(composer = TextFieldValue(), submitting = false,
                    submissionFailed = false, composerLimitReached = false,
                    active = it.active.takeUnless { active -> active == TaskTextField.Composer }) }
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            change { it.copy(submitting = false, submissionFailed = true) }
            false
        }
    }

    fun addNote(): Job {
        if (state.value?.submissionFailed == true) submission = null
        val attempt = prepareSubmission()
        return queue.submit { submit(attempt) }
    }

    fun background(): Job {
        val save = prepareFlush()
        val attempt = prepareSubmission()
        return queue.submit { save(); submit(attempt) }
    }

    fun close(onClosed: () -> Unit): Job {
        if (state.value == null) return queue.submit { onClosed() }
        if (state.value!!.closing) return queue.submit {}
        retryClose = null
        change { it.copy(closing = true) }
        val save = prepareFlush()
        val attempt = prepareSubmission()
        return queue.submit {
            save()
            submit(attempt)
            // Typing may continue while a write is suspended. A close must include those edits too.
            while (state.value?.hasTextError == false &&
                (hasUnsavedText() || state.value!!.composer.text.isNotBlank())) {
                prepareFlush()()
                // Include drafts started, or fields changed, while an earlier insertion finished.
                submit(prepareSubmission())
            }
            if (state.value?.hasTextError == false) {
                observation?.cancel()
                observation = null
                debounce?.cancel()
                mutableState.value = null
                submission = null
                retryClose = null
                retryAction = null
                session++
                onClosed()
            } else if (state.value != null) {
                change { it.copy(closing = false) }
                retryClose = onClosed
            }
        }
    }

    private fun hasUnsavedText(): Boolean {
        val s = state.value ?: return false
        // Text that can never be saved is restored on exit, so it must not hold a close open.
        return s.textFields().any { (key, field) ->
            savable(key, field.value.text).let { it != null && it != field.saved }
        }
    }

    fun retry(): Job {
        // Only a requested insertion that failed is submitted again; a new draft stays a draft.
        val resubmit = state.value?.submissionFailed == true
        if (resubmit) submission = null
        val action = retryAction
        retryAction = null
        change { it.copy(actionFailed = false) }
        action?.invoke()
        retryClose?.let { return close(it) }
        val save = prepareFlush()
        val attempt = if (resubmit) prepareSubmission() else null
        return queue.submit { save(); submit(attempt) }
    }

    fun deleteNote(id: Int): Job = queue.submit {
        try {
            withContext(io) { repo.deleteNote(id) }
            change { it.copy(edits = it.edits - id, notes = it.notes.filterNot { n -> n.id == id },
                active = it.active.takeUnless { key -> key == TaskTextField.Note(id) }) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            actionFailed { deleteNote(id) }
        }
    }

    /** Ignored once the sheet has closed: the task screen has no error display of its own. */
    fun actionFailed(retry: (() -> Unit)? = null) {
        if (state.value == null) return
        retryAction = retry
        change { it.copy(actionFailed = true) }
    }
}
