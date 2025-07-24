// ui/task/TaskViewModel.kt
package com.taskfree.app.ui.task

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskfree.app.data.entities.Category
import com.taskfree.app.data.entities.Task
import com.taskfree.app.data.entities.TaskWithCategoryInfo
import com.taskfree.app.data.repository.TaskRepository
import com.taskfree.app.domain.model.Recurrence
import com.taskfree.app.domain.model.TaskInput
import com.taskfree.app.domain.model.TaskStatus
import com.taskfree.app.notifications.NotificationScheduler
import com.taskfree.app.ui.components.DueChoice
import com.taskfree.app.ui.components.NotificationOption
import com.taskfree.app.ui.components.toInstant
import com.taskfree.app.ui.task.components.ArchiveMode
import com.taskfree.app.ui.task.components.TaskFilter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate

class TaskViewModel(
    private val appContext: Context,
    private val repo: TaskRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    /* ------------  filters ------------------ */
    // Tag used by every log in this file
    private val tag = "ReorderDebug"

    private val _filter =
        MutableStateFlow(TaskFilter())/* ------------  main task stream ---------- */

    private val orderMutex = Mutex()

    /** re-queries Room whenever date or "archived" flag changes */
    private val allTasks = channelFlow {
        var currentJob: Job? = null
        _filter.map { Triple(it.date, it.showArchived, it.version) }.distinctUntilChanged()
            .collect { (date, archived) ->
                currentJob?.cancel()
                currentJob = launch {
                    repo.observeTasksDueBy(date, archived).collect { tasks ->
                        Log.d(
                            "Stream-Reload-Reorder",
                            "emit ${tasks.map { "${it.task.id}" }} @${System.currentTimeMillis()}"
                        )
                        send(tasks)
                    }
                }
            }
    }/* ------------  UI state ------------------ */

    val allTasksUnfiltered: StateFlow<List<TaskWithCategoryInfo>> = combine(
        allTasks, _filter
    ) { tasks, filter ->
        tasks // Return all tasks regardless of status visibility
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    val uiState: StateFlow<TaskUiState> = combine(
        allTasks,
        TaskStatusFilter.visible,
        _filter
    ) { tasks, visible, filter ->
        Log.d(
            "TaskVM",
            "repo returned ${tasks.size} rows, visible=$visible, showArchived=${filter.showArchived}"
        )
        Log.d("FilterDebug", "All from repo:")
        tasks.forEach { Log.d("FilterDebug", it.task.toString()) }

        Log.d("FilterDebug", "Visible statuses: $visible")

        val visibleFiltered = tasks.filter { it.task.status in visible }

        Log.d("FilterDebug", "Visible tasks:")
        visibleFiltered.forEach { Log.d("FilterDebug", it.task.toString()) }
        TaskUiState(
            tasks = tasks.filter { it.task.status in visible },
            visibleStatuses = visible,
            filter = filter,
            isInitialLoadPending = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TaskUiState(isInitialLoadPending = true)
    )


    /* ------------  commands ------------------ */
    fun add(
        text: String, due: DueChoice, rec: Recurrence, categoryId: Int, notify: NotificationOption
    ) = launchIO {
        val reminder = notify.toInstant(due.date)
        val id = repo.createTask(TaskInput(text, due.date, rec, categoryId, reminder))
        syncReminderWithToast(id, due.date, notify)
    }

    private fun updateValues(
        task: Task,
        newText: String,
        newDue: LocalDate?,
        rec: Recurrence,
        newCategoryId: Int,
        reminderTime: Instant?
    ) = launchIO {
        /* ── snapshots before DB write ── */
        val oldReminder = task.reminderTime
        val oldDue = task.due

        /* 1 ▸ persist changes */
        repo.updateTaskDetails(task, newText, newDue, rec, newCategoryId, reminderTime)

        /* 2 ▸ did anything relevant change? */
        val dueChanged = oldDue != newDue
        val reminderChanged = oldReminder != reminderTime
        if (!dueChanged && !reminderChanged) return@launchIO   // nothing to do

        /* 3 ▸ always cancel the old alarm if it existed */
        if (oldReminder != null) {
            val toastNeeded =
                reminderTime == null          // only toast if user removed the reminder
            NotificationScheduler.cancel(appContext, task.id, showToast = toastNeeded)
        }

        /* 4 ▸ schedule the new one if present */
        if (reminderTime != null) {
            NotificationScheduler.schedule(appContext, task.id, reminderTime, showToast = true)
        }
    }


    fun updateStatus(task: Task, newStatus: TaskStatus) =
        launchIO { repo.updateTaskStatus(task, newStatus) }


    /**
     * Re-orders a visible slice of tasks and rewrites **all** order fields so they are
     * simple 0-based integers. No sparse gaps, no ×10 bodges.
     *
     * • allTasks   – full list (usually already fetched from DB)
     * • visible    – subset currently on screen (same objects as in allTasks)
     * • from / to  – indices within the *visible* list (sorted by order field)
     * • getOrder   – returns the order field you care about
     * • setOrder   – returns a *copy* of the task with a new order value
     */
    private suspend fun reorderVisibleItems(
        allTasks: List<Task>,
        visible: List<Task>,
        from: Int,
        to: Int,
        getOrder: (Task) -> Int,
        setOrder: (Task, Int) -> Task,
    ) {
        if (from == to) {
            return
        }

        /* -------------------------------------------------------------
         * 1. Work on deterministic snapshots
         * ------------------------------------------------------------ */
        val fullSorted = allTasks.sortedBy(getOrder).toMutableList()
        val visibleSorted = visible.sortedBy(getOrder).toMutableList()
        val visibleIds = visibleSorted.map { it.id }.toSet()

        /* -------------------------------------------------------------
         * 2. Re-order the visible slice
         * ------------------------------------------------------------ */
        val moved = visibleSorted.removeAt(from)
        visibleSorted.add(to, moved)

        /* -------------------------------------------------------------
         * 3. Stitch the reordered slice back into the full list
         * ------------------------------------------------------------ */
        val itVis = visibleSorted.iterator()
        val newFull = fullSorted.map { if (it.id in visibleIds) itVis.next() else it }

        /* -------------------------------------------------------------
         * 4. Renumber every task (contiguous integers starting at 0)
         *    – touch only the ones whose order actually changed
         * ------------------------------------------------------------ */
        val updates = newFull.mapIndexedNotNull { idx, task ->
            if (getOrder(task) != idx) setOrder(task, idx) else null
        }

        /* -------------------------------------------------------------
         * 5. Persist
         * ------------------------------------------------------------ */
        if (updates.isNotEmpty()) repo.updateTaskOrder(updates)
    }

    fun moveInAllCategoryPage(
        full: List<Task>, visible: List<Task>, from: Int, to: Int, onComplete: (() -> Unit)? = null
    ) = launchIO {
        orderMutex.withLock {
            reorderVisibleItems(allTasks = full,
                visible = visible,
                from = from,
                to = to,
                getOrder = { it.allCategoryPageOrder },
                setOrder = { t, ord -> t.copy(allCategoryPageOrder = ord) })
        }
        onComplete?.invoke()
    }

    fun moveInSingleCategoryPage(
        full: List<Task>, visible: List<Task>, from: Int, to: Int, onComplete: (() -> Unit)? = null
    ) = launchIO {
        orderMutex.withLock {
            reorderVisibleItems(allTasks = full,
                visible = visible,
                from = from,
                to = to,
                getOrder = { it.singleCategoryPageOrder },
                setOrder = { t, ord -> t.copy(singleCategoryPageOrder = ord) })
        }
        onComplete?.invoke()
    }

    fun updateTaskOrder(tasks: List<Task>) = launchIO {
        if (tasks.isEmpty()) return@launchIO
        Log.d(tag, "updateTaskOrder → ${tasks.size} rows")
        repo.updateTaskOrder(tasks)
    }

    fun toggleStatusVisibility(status: TaskStatus) = TaskStatusFilter.toggle(status)

    fun archive(task: Task, mode: ArchiveMode) = launchIO {
        when (mode) {
            ArchiveMode.Single -> repo.archiveSingleOccurrence(task)
            ArchiveMode.Series -> repo.archiveTask(task)
        }
    }

    fun unArchive(task: Task) = launchIO {
        repo.saveTask(task.copy(isArchived = false))
    }

    /** convenience wrapper that always uses IO dispatcher */
    private fun launchIO(block: suspend () -> Unit) = viewModelScope.launch(io) { block() }

    fun setShowArchived(value: Boolean) {
        Log.d("TaskViewModel", "Updating filter: showArchived=$value")
        _filter.update { it.copy(showArchived = value) }
    }

    fun setDate(date: LocalDate?) {
        _filter.update { it.copy(date = date) }
    }

    fun updateTitle(task: Task, newTitle: String) = viewModelScope.launch {
        updateValues(task, newTitle, task.due, task.recurrence, task.categoryId, task.reminderTime)
    }

    fun updateDue(task: Task, newDue: DueChoice) = viewModelScope.launch {
        updateValues(
            task, task.text, newDue.date, task.recurrence, task.categoryId, task.reminderTime
        )
    }

    fun updateNotification(task: Task, opt: NotificationOption) = viewModelScope.launch {
        val whenUtc = opt.toInstant(task.due)
        updateValues(task, task.text, task.due, task.recurrence, task.categoryId, whenUtc)
    }


    fun updateCategory(task: Task, newCategory: Category) = viewModelScope.launch {
        updateValues(task, task.text, task.due, task.recurrence, newCategory.id, task.reminderTime)
    }

    fun updateRecurrence(task: Task, newRecurrence: Recurrence) = viewModelScope.launch {
        updateValues(task, task.text, task.due, newRecurrence, task.categoryId, task.reminderTime)
    }

    fun forceReload() {
        // Nudge the filter so distinctUntilChanged() sees a change.
        _filter.update { it.copy(version = it.version + 1) }
    }


    private fun syncReminderWithToast(
        taskId: Int, due: LocalDate?, opt: NotificationOption
    ) {
        NotificationScheduler.cancel(appContext, taskId, true)
        opt.toInstant(due)?.let { at ->
            NotificationScheduler.schedule(appContext, taskId, at, true)
        }
    }

}
