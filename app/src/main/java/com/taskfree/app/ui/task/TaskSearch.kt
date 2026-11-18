package com.taskfree.app.ui.task

import androidx.compose.runtime.saveable.listSaver
import com.taskfree.app.data.entities.TaskNote
import com.taskfree.app.data.entities.TaskWithCategoryInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

data class NoteSearchTarget(val noteId: Int, val offset: Int, val length: Int = 1) {
    companion object {
        val Saver = listSaver<NoteSearchTarget?, Int>(
            save = { target -> target?.let { listOf(it.noteId, it.offset, it.length) } ?: emptyList() },
            restore = { if (it.isEmpty()) null else NoteSearchTarget(it[0], it[1], it[2]) }
        )
    }
}

internal data class TaskSearchInput(
    val tasks: List<TaskWithCategoryInfo> = emptyList(),
    val query: String = ""
)

/** Only entering/leaving search restarts Room; typing and task writes reuse its latest snapshot. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
internal fun observeTaskSearch(
    input: StateFlow<TaskSearchInput>,
    notes: Flow<List<TaskNote>>,
    onFailure: (Throwable) -> Unit
): Flow<TaskSearchResult> = input.map { it.query.isNotEmpty() }.distinctUntilChanged()
    .flatMapLatest { active ->
        if (!active) flowOf(TaskSearchResult())
        else combine(
            input.map { it.tasks }.distinctUntilChanged(),
            input.map { it.query }.distinctUntilChanged().debounce(150),
            // If notes can't be read, title matches still work.
            notes.catch {
                onFailure(it)
                emit(emptyList())
            }
        ) { tasks, query, newestFirstNotes -> searchTaskText(tasks, newestFirstNotes, query) }
    }

internal fun taskSearchOffset(text: String, query: String): Int = text.indexOf(query, ignoreCase = true)

internal fun matchesTaskSearch(text: String, query: String): Boolean = taskSearchOffset(text, query) >= 0

data class TaskSearchResult(
    val query: String = "",
    val taskIds: Set<Int> = emptySet(),
    /** The newest matching note of each task found only through its notes. */
    val noteTargets: Map<Int, NoteSearchTarget> = emptyMap()
)

/** Notes arrive in the same newest-first order used by the task sheet. */
internal fun searchTaskText(
    tasks: List<TaskWithCategoryInfo>,
    newestFirstNotes: List<TaskNote>,
    query: String
): TaskSearchResult {
    val eligibleIds = tasks.mapTo(hashSetOf()) { it.task.id }
    if (query.isEmpty()) return TaskSearchResult(query, eligibleIds)

    val matches = tasks.asSequence()
        .filter { matchesTaskSearch(it.task.text, query) }
        .mapTo(hashSetOf()) { it.task.id }
    val noteTargets = mutableMapOf<Int, NoteSearchTarget>()
    for (note in newestFirstNotes) {
        // A title match needs no note; later matching notes cannot duplicate a task.
        if (note.taskId !in eligibleIds || note.taskId in matches) continue
        val offset = taskSearchOffset(note.text, query)
        if (offset < 0) continue
        matches += note.taskId
        noteTargets[note.taskId] = NoteSearchTarget(note.id, offset, query.length)
    }
    return TaskSearchResult(query, matches, noteTargets)
}
