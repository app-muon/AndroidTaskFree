package com.taskfree.app.ui.task

import com.taskfree.app.data.entities.Category
import com.taskfree.app.data.entities.TaskNote
import com.taskfree.app.data.entities.TaskWithCategoryInfo
import com.taskfree.app.testutil.task
import com.taskfree.app.ui.task.components.TaskListState
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class TaskSearchTest {
    private val category = Category(1, "Home", 0)
    private fun row(id: Int, title: String) = TaskWithCategoryInfo(task(id = id, categoryId = 1, text = title), category)
    private fun note(id: Int, taskId: Int, text: String) = TaskNote(id, taskId, text, Instant.ofEpochSecond(id.toLong()))

    @Test
    fun `titles and notes use the same trimmed case insensitive search without duplicate tasks`() {
        val result = searchTaskText(
            listOf(row(1, "Buy MILK"), row(2, "Shopping"), row(3, "No match")),
            listOf(note(5, 9, "Milk"), note(4, 2, "Newest milk note"),
                note(3, 2, "Older milk note"), note(2, 1, "Milk note")),
            TaskListState(searchText = "  mIlK  ").searchQuery
        )
        assertEquals(setOf(1, 2), result.taskIds)
        assertEquals(setOf(2), result.noteTargets.keys)
        assertEquals(4, result.noteTargets.getValue(2).noteId)
        assertEquals("mIlK", result.query)
    }

    @Test
    fun `phrases must occur in one field and punctuation is literal`() {
        val rows = listOf(row(1, "Policy"), row(2, "Other"))
        val notes = listOf(note(3, 2, "Policy number 100%_[AB]"),
            note(2, 1, "Policy"), note(1, 1, "number"))
        assertEquals(setOf(2), searchTaskText(rows, notes, "policy number").taskIds)
        assertEquals(setOf(2), searchTaskText(rows, notes, "100%_[AB]").taskIds)
        assertTrue(searchTaskText(rows, notes, "100%_[A.]").taskIds.isEmpty())
    }

    @Test
    fun `search reaches beyond the newest five notes and the end of long notes`() {
        val longText = "Earlier context. ".repeat(100) + "Policy AB123 is valid."
        val notes = (8 downTo 2).map { note(it, 1, "Unrelated") } + note(1, 1, longText)
        val target = searchTaskText(listOf(row(1, "Insurance")), notes, "ab123").noteTargets.getValue(1)
        assertEquals(NoteSearchTarget(1, longText.indexOf("AB123"), 5), target)
    }

    @Test
    fun `note targets point at the match across line breaks and unicode`() {
        val text = "😀\nBefore\n\tMÉDICO\r\nAfter"
        val target = searchTaskText(listOf(row(1, "Appointment")), listOf(note(1, 1, text)), "médico")
            .noteTargets.getValue(1)
        assertEquals("MÉDICO", text.substring(target.offset, target.offset + target.length))
    }

    @Test
    fun `blank search returns all candidate tasks without note targets`() {
        val result = searchTaskText(listOf(row(1, "First"), row(2, "Second")),
            listOf(note(1, 1, "Anything")), TaskListState(searchText = " \n ").searchQuery)
        assertEquals(setOf(1, 2), result.taskIds)
        assertTrue(result.noteTargets.isEmpty())
    }

    @Test
    fun `typing and task edits reuse notes and never clear completed results`() = runTest {
        val input = MutableStateFlow(TaskSearchInput())
        val notes = MutableStateFlow(listOf(note(1, 1, "AB123 policy")))
        var subscriptions = 0
        var cancellations = 0
        val observed = flow {
            subscriptions++
            try { emitAll(notes) } finally { cancellations++ }
        }
        val results = mutableListOf<TaskSearchResult>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeTaskSearch(input, observed) { throw it }.collect { results += it }
        }
        runCurrent()
        assertEquals(0, subscriptions)
        input.value = TaskSearchInput(listOf(row(1, "Insurance")), "AB")
        advanceTimeBy(151)
        runCurrent()
        assertEquals(setOf(1), results.last().taskIds)
        results.clear()

        input.value = input.value.copy(tasks = listOf(row(1, "Renamed insurance")))
        runCurrent()
        input.value = input.value.copy(tasks = input.value.tasks.map {
            it.copy(task = it.task.copy(allCategoryPageOrder = 9))
        })
        runCurrent()
        input.value = input.value.copy(query = "AB1")
        advanceTimeBy(75)
        input.value = input.value.copy(query = "AB123")
        runCurrent()
        assertEquals("AB", results.last().query)
        advanceTimeBy(151)
        runCurrent()
        assertEquals("AB123", results.last().query)
        assertTrue(results.all { it.taskIds == setOf(1) })
        assertFalse(results.any { it.query == "AB1" })
        assertEquals(1, subscriptions)
        assertEquals(0, cancellations)

        input.value = input.value.copy(query = "")
        runCurrent()
        assertEquals(1, cancellations)
        assertEquals("", results.last().query)
        input.value = input.value.copy(query = "AB123")
        advanceTimeBy(151)
        runCurrent()
        assertEquals(2, subscriptions)
        assertEquals(setOf(1), results.last().taskIds)
    }

    @Test
    fun `note failures preserve title search and report the failure`() = runTest {
        val input = MutableStateFlow(TaskSearchInput(listOf(row(1, "AB123 renewal"), row(2, "Insurance")), "AB123"))
        val failure = IOException("Storage unavailable")
        val failures = mutableListOf<Throwable>()
        val results = mutableListOf<TaskSearchResult>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeTaskSearch(input, flow { throw failure }, failures::add).collect { results += it }
        }
        advanceTimeBy(151)
        runCurrent()
        assertEquals(listOf(failure), failures)
        assertEquals(setOf(1), results.last().taskIds)
        input.value = input.value.copy(query = "Insurance")
        advanceTimeBy(151)
        runCurrent()
        assertEquals(setOf(2), results.last().taskIds)
    }
}
