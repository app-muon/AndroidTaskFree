// ui/task/TaskViewModel.kt
package com.taskfree.app.ui.task

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskfree.app.R
import com.taskfree.app.data.entities.Task
import com.taskfree.app.data.entities.TaskWithCategoryInfo
import com.taskfree.app.data.entities.repeatRule
import com.taskfree.app.data.entities.repeatRuleOrNull
import com.taskfree.app.data.repository.TaskRepository
import com.taskfree.app.domain.model.Recurrence
import com.taskfree.app.domain.model.ReminderResult
import com.taskfree.app.domain.model.RepeatRule
import com.taskfree.app.domain.model.TaskInput
import com.taskfree.app.domain.model.TaskEditException
import com.taskfree.app.domain.model.TaskEditFailureReason
import com.taskfree.app.domain.model.RecurrenceValidationResult
import com.taskfree.app.domain.model.taskEditFailureReason
import com.taskfree.app.domain.model.validateRecurrenceDate
import com.taskfree.app.domain.model.TaskStatus
import com.taskfree.app.domain.model.resolveReminderInstant
import com.taskfree.app.notifications.NotificationScheduler
import com.taskfree.app.ui.components.DueChoice
import com.taskfree.app.ui.components.NotificationOption
import com.taskfree.app.ui.components.toInstant
import com.taskfree.app.ui.task.components.ArchiveMode
import com.taskfree.app.ui.task.components.TaskDialogs
import com.taskfree.app.ui.task.components.TaskFilter
import com.taskfree.app.ui.task.components.computeReorderUpdates
import com.taskfree.app.util.AppDateProvider
import com.taskfree.app.util.DateProvider
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.withContext

class TaskViewModel(
    private val appContext: Context,
    private val repo: TaskRepository,
    private val dateProvider: DateProvider = AppDateProvider.current,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val mutations: TaskMutationQueue = TaskMutationQueue(viewModelScope) {
        Log.e("TaskViewModel", "Task operation failed", it)
        sheetEditor.actionFailed()
    }
    private val sheetEditor: TaskSheetEditor = TaskSheetEditor(repo, viewModelScope, io, mutations)
    val sheetState = sheetEditor.state
    private val _dialogs = MutableStateFlow<TaskDialogs>(TaskDialogs.None)
    val dialogs: StateFlow<TaskDialogs> = _dialogs
    private var dialogStateInitialized = false
    val noteCounts = repo.observeNoteCounts().map { counts -> counts.associate { it.taskId to it.count } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun setDialog(dialog: TaskDialogs) {
        dialogStateInitialized = true
        if (sheetState.value == null && dialog is TaskDialogs.Options) {
            sheetEditor.open(dialog.task.task)
            _dialogs.value = dialog
        } else if (sheetState.value != null && dialog !is TaskDialogs.Options) {
            sheetEditor.close { _dialogs.value = dialog }
        } else if (dialog is TaskDialogs.Options && sheetState.value?.taskId != dialog.task.task.id) {
            sheetEditor.close {
                sheetEditor.open(dialog.task.task)
                _dialogs.value = dialog
            }
        } else {
            _dialogs.value = dialog
        }
    }

    fun focusText(field: TaskTextField?) = sheetEditor.focus(field)
    fun blurText(field: TaskTextField) = sheetEditor.blur(field)
    fun cancelTitleEdit() = sheetEditor.cancelTitle()
    fun editText(field: TaskTextField, value: TextFieldValue, inputToken: TaskTextInputToken) =
        sheetEditor.edit(field, value, inputToken)
    fun addNote() = sheetEditor.addNote()
    fun deleteNote(id: Int) = sheetEditor.deleteNote(id)
    fun retryTextSave() = sheetEditor.retry()
    fun onBackground() = sheetEditor.background()
    fun closeSheet(onClosed: () -> Unit) = sheetEditor.close(onClosed)

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
        rec: RepeatRule,
        categoryId: Int,
        notify: NotificationOption
    ) = launchIO {
        // Persist the user's intent (even if it's past/blocked)
        val intendedReminder = notify.toInstant(due.date)
        createAndSchedule(TaskInput(text, due.date, rec, categoryId, intendedReminder), notify)
    }

    fun clone(taskId: Int, titleTemplate: String) = launchIO(taskId, needsTitle = true, needsNotes = true) {
        val current = repo.taskById(taskId) ?: return@launchIO
        val notify = NotificationOption.fromTask(current)
        val id = repo.cloneTask(
            taskId,
            TaskInput(
                titleTemplate.format(current.text), current.due, current.repeatRule,
                current.categoryId, notify.toInstant(current.due)
            )
        ) ?: return@launchIO
        scheduleCreatedTask(id, notify)
    }

    private suspend fun createAndSchedule(input: TaskInput, notify: NotificationOption) {
        val id = repo.createTask(input)
        scheduleCreatedTask(id, notify)
    }

    private suspend fun scheduleCreatedTask(id: Int, notify: NotificationOption) {
        // Evaluate scheduling policy using the created task snapshot
        val task = repo.taskById(id) ?: return
        when (val result = task.resolveReminderInstant(notify, task.due)) {
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



    fun updateStatus(taskId: Int, newStatus: TaskStatus) = launchIO(taskId, needsTitle = true) {
        val before = repo.taskById(taskId) ?: return@launchIO

        val wasDone = before.status == TaskStatus.DONE
        val nowDone = newStatus == TaskStatus.DONE

        val res = repo.updateTaskStatus(taskId, newStatus)

        // Re-read the updated task snapshot
        val updated = repo.taskById(taskId) ?: return@launchIO
        val oldReminder = before.reminderTime
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


    /** Re-orders a visible slice of tasks and persists the renumbered rows. */
    private suspend fun reorderVisibleItems(
        allTasks: List<Task>,
        visible: List<Task>,
        from: Int,
        to: Int,
        getOrder: (Task) -> Int,
        setOrder: (Task, Int) -> Task,
    ) {
        val updates = computeReorderUpdates(allTasks, visible, from, to, getOrder, setOrder)
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

    fun archive(task: Task, mode: ArchiveMode) = launchIO(task.id, needsTitle = true) {
        val current = repo.taskById(task.id) ?: return@launchIO
        when (mode) {
            ArchiveMode.Single -> {
                val nextId = repo.archiveSingleOccurrence(current)
                NotificationScheduler.cancel(appContext, current.id, current.reminderTime)
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

            ArchiveMode.Series -> repo.archiveSeries(current.id).forEach { archived ->
                NotificationScheduler.cancel(appContext, archived.id, archived.reminderTime)
            }
        }
    }


    fun unArchive(task: Task) = launchIO(task.id) {
        // Persist unarchive
        repo.unarchiveTask(task.id)

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

    fun deletePermanently(task: Task) = launchIO(task.id) {
        NotificationScheduler.cancel(appContext, task.id, task.reminderTime)
        repo.deleteTaskPermanently(task)
    }


    /**
     * Enqueue the captured edits and await them before a mutation reads the task. Only an
     * operation that copies text ([needsTitle], [needsNotes]) requires those saves to succeed.
     */
    private fun launchIO(
        taskId: Int? = null,
        needsTitle: Boolean = false,
        needsNotes: Boolean = false,
        block: suspend () -> Unit
    ): Job {
        val flush = if (sheetState.value?.taskId == taskId && taskId != null)
            sheetEditor.prepareFlush(requireNotes = needsNotes) else null
        return mutations.submit {
            val textSaved = flush == null || flush()
            if (textSaved || (!needsTitle && !needsNotes)) {
                try {
                    withContext(io) { block() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("TaskViewModel", "Task operation failed", e)
                    sheetEditor.actionFailed(e.taskEditFailureReason()) { launchIO(taskId, needsTitle, needsNotes, block) }
                }
            }
        }
    }

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
        val repeatRule: FieldEdit<RepeatRule> = FieldEdit.NoChange,
        val categoryId: FieldEdit<Int> = FieldEdit.NoChange,
        val notify: FieldEdit<NotificationOption> = FieldEdit.NoChange
    )

    fun applyEdits(
        taskId: Int,
        edits: TaskEdits,
        requireEditableRepeat: Boolean = false,
        onResult: ((Result<Task>) -> Unit)? = null
    ): Job = enqueueEdits(taskId, edits, requireEditableRepeat,
        reportSheetFailure = !requireEditableRepeat, onResult = onResult)

    private fun enqueueEdits(
        taskId: Int,
        edits: TaskEdits,
        requireEditableRepeat: Boolean,
        reportSheetFailure: Boolean,
        onResult: ((Result<Task>) -> Unit)?
    ): Job {
        val sessionId = sheetState.value?.takeIf { it.taskId == taskId }?.sessionId
        val flush = if (sheetState.value?.taskId == taskId) sheetEditor.prepareFlush() else null
        return mutations.submit {
            var oldReminder: Instant? = null
            val result = try {
                flush?.invoke()
                val saved = withContext(io) {
                    repo.updateTaskDetails(taskId, requireEditableRepeat) { current ->
                        oldReminder = current.reminderTime
                        val due = edits.due.resolve(current.due) { null }
                        // An explicit repair must not evaluate the corrupt stored rule first.
                        val rule = when (val change = edits.repeatRule) {
                            is FieldEdit.Set -> change.value
                            FieldEdit.Clear -> RepeatRule(Recurrence.NONE)
                            FieldEdit.NoChange -> current.repeatRuleOrNull
                                ?: throw TaskEditException(TaskEditFailureReason.INVALID_RULE)
                        }
                        val reminder = when (val notify = edits.notify) {
                            FieldEdit.NoChange -> current.reminderTime
                            FieldEdit.Clear -> null
                            is FieldEdit.Set -> notify.value.toInstant(due)
                        }
                        TaskInput(
                            edits.title.resolve(current.text) { current.text }, due, rule,
                            edits.categoryId.resolve(current.categoryId) { current.categoryId }, reminder
                        )
                    }
                }
                Result.success(saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("TaskViewModel", "Task edit failed for $taskId", e)
                if (reportSheetFailure && isSheetSession(taskId, sessionId)) {
                    sheetEditor.actionFailed(e.taskEditFailureReason()) {
                        applyEdits(taskId, edits, requireEditableRepeat, onResult)
                    }
                }
                Result.failure(e)
            }
            result.getOrNull()?.let { saved ->
                // A committed edit is immediately authoritative, even if its alarm fails.
                val dialog = _dialogs.value
                if (dialog is TaskDialogs.Options && dialog.task.task.id == taskId) {
                    _dialogs.value = dialog.copy(task = dialog.task.copy(task = saved))
                }
                if (isSheetSession(taskId, sessionId)) sheetEditor.clearActionFailure()
            }
            // Completion is outside the persistence catch: it must never turn a commit into a retry.
            onResult?.invoke(result)
            result.getOrNull()?.let { saved ->
                try {
                    withContext(io) { reconcileReminder(saved, oldReminder) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("TaskViewModel", "Reminder update failed after saving task $taskId", e)
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(appContext, R.string.task_saved_reminder_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun reconcileReminder(task: Task, oldReminder: Instant?) {
        when (val result = task.resolveReminderInstant(NotificationOption.fromTask(task), task.due)) {
            is ReminderResult.Scheduled ->
                NotificationScheduler.reschedule(appContext, task.id, oldReminder, result.instant)
            ReminderResult.InPast -> {
                NotificationScheduler.showToast(appContext, NotificationScheduler.ToastKind.InPast)
                NotificationScheduler.cancel(appContext, task.id, oldReminder)
            }
            ReminderResult.Blocked -> NotificationScheduler.cancel(appContext, task.id, oldReminder)
        }
    }

    data class RepeatSaveState(
        val taskId: Int? = null,
        val saving: Boolean = false,
        val saved: Task? = null,
        val failed: Boolean = false,
        val failureReason: TaskEditFailureReason? = null
    )
    private val _repeatSave = MutableStateFlow(RepeatSaveState())
    val repeatSave: StateFlow<RepeatSaveState> = _repeatSave

    fun resetRepeatSave() {
        if (!_repeatSave.value.saving) _repeatSave.value = RepeatSaveState()
    }

    fun saveRepeat(taskId: Int, rule: RepeatRule, due: LocalDate?) {
        if (_repeatSave.value.saving) return
        _repeatSave.value = RepeatSaveState(taskId, saving = true)
        applyEdits(taskId, TaskEdits(repeatRule = FieldEdit.Set(rule), due = FieldEdit.Set(due)),
            requireEditableRepeat = true) { result ->
            _repeatSave.value = RepeatSaveState(taskId, saved = result.getOrNull(), failed = result.isFailure,
                failureReason = result.exceptionOrNull()?.taskEditFailureReason())
        }
    }

    data class QuickDateSaveState(
        val taskId: Int,
        val sessionId: Long,
        val due: LocalDate,
        val saving: Boolean = false,
        val validation: RecurrenceValidationResult? = null,
        val failureReason: TaskEditFailureReason? = null
    )

    private val _quickDateSaves = MutableStateFlow<Map<Int, QuickDateSaveState>>(emptyMap())
    val quickDateSaves: StateFlow<Map<Int, QuickDateSaveState>> = _quickDateSaves

    private fun isSheetSession(taskId: Int, sessionId: Long?): Boolean =
        sheetState.value?.let { it.taskId == taskId && it.sessionId == sessionId } == true

    fun saveQuickDate(task: Task, due: LocalDate) {
        val sheet = sheetState.value?.takeIf { it.taskId == task.id && !it.closing } ?: return
        if (_quickDateSaves.value[task.id]?.saving == true) return
        val validation = validateRecurrenceDate(task.repeatRuleOrNull, due)
        val operation = QuickDateSaveState(task.id, sheet.sessionId, due,
            validation = validation.takeUnless { it == RecurrenceValidationResult.Ok })
        if (operation.validation != null) {
            _quickDateSaves.update { it + (task.id to operation) }
        } else startQuickDate(operation)
    }

    private fun startQuickDate(operation: QuickDateSaveState) {
        if (_quickDateSaves.value[operation.taskId]?.saving == true) return
        _quickDateSaves.update { it + (operation.taskId to operation.copy(saving = true, failureReason = null)) }
        enqueueEdits(operation.taskId, TaskEdits(due = FieldEdit.Set(operation.due)),
            requireEditableRepeat = false, reportSheetFailure = false) { result ->
            val reason = result.exceptionOrNull()?.taskEditFailureReason()
            _quickDateSaves.update { it + (operation.taskId to operation.copy(failureReason = reason)) }
            if (isSheetSession(operation.taskId, operation.sessionId)) {
                if (result.isSuccess) {
                    // This callback belongs to the ViewModel and survives rotation. A reopened
                    // sheet (even for this task) has a different session and is never dismissed.
                    sheetEditor.close { _dialogs.value = TaskDialogs.None }
                } else {
                    sheetEditor.actionFailed(reason ?: TaskEditFailureReason.STORAGE) {
                        startQuickDate(operation)
                    }
                }
            }
        }
    }

    suspend fun openTaskOptions(taskId: Int, canOpen: () -> Boolean = { true }): Boolean? {
        if (!canOpen()) return null
        if ((_dialogs.value as? TaskDialogs.Options)?.task?.task?.id == taskId) return true
        val task = withContext(io) { repo.taskWithCategory(taskId) }
        if (!canOpen()) return null
        if (task == null) return false
        setDialog(TaskDialogs.Options(task))
        return true
    }

    suspend fun restoreDialog(kind: String, taskId: Int, categoryId: Int, due: DueChoice) {
        // A retained ViewModel may already have completed an operation during rotation.
        // Its closed state takes precedence over the activity's older saved navigation ID.
        if (dialogStateInitialized) return
        dialogStateInitialized = true
        val dialog = withContext(io) {
            when (kind) {
                "options" -> repo.taskWithCategory(taskId)?.let { TaskDialogs.Options(it) }
                "clone" -> repo.taskWithCategory(taskId)?.let { TaskDialogs.ConfirmClone(it) }
                "delete" -> repo.taskWithCategory(taskId)?.let { TaskDialogs.ConfirmPermanentDelete(it) }
                "archive_single" -> repo.taskWithCategory(taskId)?.let { TaskDialogs.ConfirmArchive(it, ArchiveMode.Single) }
                "archive_series" -> repo.taskWithCategory(taskId)?.let { TaskDialogs.ConfirmArchive(it, ArchiveMode.Series) }
                "add" -> repo.activeCategory(categoryId)?.let { TaskDialogs.Add(it, due) }
                else -> null
            }
        }
        if (_dialogs.value == TaskDialogs.None && dialog != null) setDialog(dialog)
    }

    suspend fun loadTask(taskId: Int): Task? = withContext(io) { repo.taskById(taskId) }
    suspend fun editableRepeatTask(taskId: Int): Task? = withContext(io) { repo.editableRepeatTask(taskId) }

    fun forceReload() {
        // Nudge the filter so distinctUntilChanged() sees a change.
        _filter.update { it.copy(version = it.version + 1) }
    }

}
