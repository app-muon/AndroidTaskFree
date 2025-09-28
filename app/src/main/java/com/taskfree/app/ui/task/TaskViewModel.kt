// ui/task/TaskViewModel.kt
package com.taskfree.app.ui.task

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskfree.app.data.entities.Task
import com.taskfree.app.data.entities.TaskWithCategoryInfo
import com.taskfree.app.data.repository.TaskRepository
import com.taskfree.app.domain.model.Recurrence
import com.taskfree.app.domain.model.ReminderResult
import com.taskfree.app.domain.model.TaskInput
import com.taskfree.app.domain.model.TaskStatus
import com.taskfree.app.domain.model.resolveReminderInstant
import com.taskfree.app.notifications.NotificationScheduler
import com.taskfree.app.ui.components.DueChoice
import com.taskfree.app.ui.components.NotificationOption
import com.taskfree.app.ui.components.toInstant
import com.taskfree.app.ui.task.components.ArchiveMode
import com.taskfree.app.ui.task.components.TaskFilter
import com.taskfree.app.util.AppDateProvider
import com.taskfree.app.util.DateProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import java.time.LocalDate

class TaskViewModel(
    private val appContext: Context,
    private val repo: TaskRepository,
    private val dateProvider: DateProvider = AppDateProvider.current,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    /* ------------  filters ------------------ */
    // Tag used by every log in this file
    private val tag = "ReorderDebug"

    private val _filter =
        MutableStateFlow(TaskFilter())/* ------------  main task stream ---------- */

    // Check for day changes when app resumes. And resume with previously selected statuses
    init {
        TaskStatusFilter.init(appContext)
        viewModelScope.launch {
            checkForDayChange()
        }
    }

    private suspend fun checkForDayChange() {
        var lastKnownDate = dateProvider.today()

        while (true) {
            delay(60_000) // Check every minute
            val currentDate = dateProvider.today()

            if (currentDate != lastKnownDate) {
                Log.d("TaskViewModel", "Day changed from $lastKnownDate to $currentDate")

                _filter.value.date?.let { filterDate ->
                    if (filterDate == lastKnownDate) {
                        setDate(currentDate)
                    }
                }

                lastKnownDate = currentDate
            }
        }
    }

    fun refreshToday() {
        val today = dateProvider.today()
        val currentFilterDate = _filter.value.date

        if (currentFilterDate != null && currentFilterDate != today) {
            Log.d("TaskViewModel", "Refreshing today from $currentFilterDate to $today")
            setDate(today)
        }
    }

    private val orderMutex = Mutex()

    /** re-queries Room whenever date or "archived" flag changes */
    private val allTasks = channelFlow {
        var currentJob: Job? = null
        _filter.map { Triple(it.date, it.showArchived, it.version) }.distinctUntilChanged()
            .collect { (date, archived, _) ->
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
        text: String,
        due: DueChoice,
        rec: Recurrence,
        categoryId: Int,
        notify: NotificationOption
    ) = launchIO {
        // Persist the user's intent (even if it's past/blocked)
        val intendedReminder = notify.toInstant(due.date)
        val id = repo.createTask(TaskInput(text, due.date, rec, categoryId, intendedReminder))

        // Evaluate scheduling policy using the created task snapshot
        val task = repo.taskById(id) ?: return@launchIO
        when (val result = task.resolveReminderInstant(notify, due.date)) {
            is ReminderResult.Scheduled -> {
                NotificationScheduler.schedule(appContext, id, result.instant)
            }
            ReminderResult.InPast -> {
                // just a toast; no schedule
                NotificationScheduler.showToast(appContext, NotificationScheduler.ToastKind.InPast)
            }
            ReminderResult.Blocked -> Unit // nothing to do
        }
    }



    fun updateStatus(task: Task, newStatus: TaskStatus) = launchIO {
        val wasDone = task.status == TaskStatus.DONE
        val nowDone = newStatus == TaskStatus.DONE

        val res = repo.updateTaskStatus(task, newStatus)

        // Re-read the updated task snapshot
        val updated = repo.taskById(task.id) ?: return@launchIO
        val oldReminder = task.reminderTime
        val notify = NotificationOption.fromTask(updated)

        val result = updated.resolveReminderInstant(notify, updated.due)

        when {
            nowDone && !wasDone -> {
                // Moving TO DONE → always cancel this task’s alarm
                NotificationScheduler.cancel(appContext, updated.id, oldReminder)

                // If a "next" instance was created, schedule for it if eligible
                res.nextCreatedId?.let { nextId ->
                    val next = repo.taskById(nextId) ?: return@let
                    val nextResult =
                        next.resolveReminderInstant(NotificationOption.fromTask(next), next.due)
                    when (nextResult) {
                        is ReminderResult.Scheduled -> NotificationScheduler.schedule(
                            appContext,
                            next.id,
                            nextResult.instant
                        )
                        ReminderResult.InPast,
                        ReminderResult.Blocked -> {
                            // Do nothing: suppress toast for auto-created next tasks
                        }
                    }
                }
            }

            !nowDone && wasDone -> {
                // Moving FROM DONE → re-evaluate current task
                when (result) {
                    is ReminderResult.Scheduled -> NotificationScheduler.schedule(
                        appContext,
                        updated.id,
                        result.instant
                    )

                    ReminderResult.InPast -> NotificationScheduler.showToast(
                        appContext,
                        NotificationScheduler.ToastKind.InPast
                    )

                    ReminderResult.Blocked -> NotificationScheduler.cancel(
                        appContext,
                        updated.id,
                        oldReminder
                    )
                }

                // Clean up a deleted "next" instance alarm if repo indicates it
                res.nextDeletedId?.let { deletedId ->
                    NotificationScheduler.cancel(appContext, deletedId, null)
                }
            }

            else -> {
                // Status didn’t cross DONE boundary; nothing special to do
            }
        }
    }


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
            reorderVisibleItems(
                allTasks = full,
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
            reorderVisibleItems(
                allTasks = full,
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
        // Always cancel this task’s alarm when archiving
        NotificationScheduler.cancel(appContext, task.id, task.reminderTime)

        when (mode) {
            ArchiveMode.Single -> {
                val nextId = repo.archiveSingleOccurrence(task)
                // If a next instance was created, schedule it if eligible
                nextId?.let { id ->
                    val next = repo.taskById(id) ?: return@let
                    val result =
                        next.resolveReminderInstant(NotificationOption.fromTask(next), next.due)
                    when (result) {
                        is ReminderResult.Scheduled -> NotificationScheduler.schedule(
                            appContext,
                            next.id,
                            result.instant
                        )

                        ReminderResult.InPast -> NotificationScheduler.showToast(
                            appContext,
                            NotificationScheduler.ToastKind.InPast
                        )

                        ReminderResult.Blocked -> Unit
                    }
                }
            }

            ArchiveMode.Series -> repo.archiveTask(task)
        }
    }


    fun unArchive(task: Task) = launchIO {
        // Persist unarchive
        repo.saveTask(task.copy(isArchived = false))

        // Re-read and evaluate scheduling
        val updated = repo.taskById(task.id) ?: return@launchIO
        val result =
            updated.resolveReminderInstant(NotificationOption.fromTask(updated), updated.due)

        when (result) {
            is ReminderResult.Scheduled -> NotificationScheduler.schedule(
                appContext,
                updated.id,
                result.instant
            )

            ReminderResult.InPast -> NotificationScheduler.showToast(
                appContext,
                NotificationScheduler.ToastKind.InPast
            )

            ReminderResult.Blocked -> Unit
        }
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

    data class TaskEdits(
        val title: FieldEdit<String> = FieldEdit.NoChange,
        val due: FieldEdit<LocalDate?> = FieldEdit.NoChange,
        val recurrence: FieldEdit<Recurrence> = FieldEdit.NoChange,
        val categoryId: FieldEdit<Int> = FieldEdit.NoChange,
        val notify: FieldEdit<NotificationOption> = FieldEdit.NoChange
    )

    fun applyEdits(taskId: Int, edits: TaskEdits) = launchIO {
        val current = repo.taskById(taskId) ?: return@launchIO
        val oldReminder = current.reminderTime
        val newTitle = edits.title.resolve(current.text) { current.text }
        val newDue = edits.due.resolve(current.due) { null }
        val newRecurrence = edits.recurrence.resolve(current.recurrence) { Recurrence.NONE }
        val newCategoryId = edits.categoryId.resolve(current.categoryId) { current.categoryId }

        val effectiveNotify = edits.notify.resolve(
            current = NotificationOption.fromTask(current)
        ) { NotificationOption.None }

        // Always persist the user's intent (even if in the past)
        val intendedReminder = effectiveNotify.toInstant(newDue)
        repo.updateTaskDetails(
            task = current,
            newTitle = newTitle,
            newDueDate = newDue,
            newRecurrence = newRecurrence,
            newCategoryId = newCategoryId,
            newReminderTime = intendedReminder
        )

        // Decide what to do with OS alarms/toasts
        val postEditTask = current.copy(due = newDue, recurrence = newRecurrence) // status/isArchived unchanged
        when (val result = postEditTask.resolveReminderInstant(effectiveNotify, newDue)) {
            is ReminderResult.Scheduled -> {
                NotificationScheduler.reschedule(appContext, current.id, oldReminder, result.instant)
            }
            ReminderResult.InPast -> {
                // No OS schedule; let the user know it wasn’t scheduled
                NotificationScheduler.showToast(appContext, NotificationScheduler.ToastKind.InPast)
                // If there was an old alarm, ensure it’s not hanging around
                if (oldReminder != null) NotificationScheduler.cancel(appContext, current.id, oldReminder)
            }
            ReminderResult.Blocked -> {
                // Ensure any existing alarm is canceled
                if (oldReminder != null) NotificationScheduler.cancel(appContext, current.id, oldReminder)
            }
        }    }


    fun forceReload() {
        // Nudge the filter so distinctUntilChanged() sees a change.
        _filter.update { it.copy(version = it.version + 1) }
    }

}
