// TaskEditValidation
package com.taskfree.app.ui.task.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.taskfree.app.R
import com.taskfree.app.data.entities.Task
import com.taskfree.app.domain.model.NotificationValidationResult
import com.taskfree.app.domain.model.Recurrence
import com.taskfree.app.domain.model.RecurrenceValidationResult
import com.taskfree.app.domain.model.validateNotification
import com.taskfree.app.domain.model.validateRecurrenceDate
import com.taskfree.app.ui.components.DueChoice
import com.taskfree.app.ui.components.NotificationOption
import com.taskfree.app.ui.components.PanelConstants
import com.taskfree.app.ui.task.FieldEdit
import com.taskfree.app.ui.task.TaskEditState
import com.taskfree.app.ui.task.TaskViewModel
import com.taskfree.app.ui.task.toFieldEdit
import com.taskfree.app.ui.theme.providePanelColors

@Composable
fun ValidationRecurrenceErrorText(
    result: RecurrenceValidationResult
) {
    val errorText = when (result) {
        is RecurrenceValidationResult.NotWeekday -> stringResource(
            R.string.incompatibility_not_weekday, result.date
        )

        is RecurrenceValidationResult.NotWeekend -> stringResource(
            R.string.incompatibility_not_weekend, result.date
        )

        is RecurrenceValidationResult.MissingDueDate -> stringResource(
            R.string.a_due_date_is_required_for_recurring_tasks
        )

        else -> ""
    }

    if (errorText.isNotEmpty()) {
        Text(
            text = errorText,
            color = providePanelColors().errorText,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(
                start = PanelConstants.HORIZONTAL_PADDING, top = PanelConstants.ERROR_TOP_PADDING
            )
        )
    }
}

@Composable
fun ValidationNotificationErrorText(
    result: NotificationValidationResult
) {
    val errorText = when (result) {
        is NotificationValidationResult.MissingDueDate -> stringResource(
            R.string.missing_due_for_notification
        )

        else -> ""
    }

    if (errorText.isNotEmpty()) {
        Text(
            text = errorText,
            color = providePanelColors().errorText,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(
                start = PanelConstants.HORIZONTAL_PADDING, top = PanelConstants.ERROR_TOP_PADDING
            )
        )
    }
}

fun commitDue(
    candidate: DueChoice, state: TaskEditState, task: Task, vm: TaskViewModel
) {
    val v = validateRecurrenceDate(state.recurrence, candidate)
    if (v is RecurrenceValidationResult.Ok) {
        // If due is being cleared, also clear notification
        val clearingDue = candidate.date == null
        val notifyEdit: FieldEdit<NotificationOption> =
            if (clearingDue && state.currentNotifyOption != NotificationOption.None) {
                FieldEdit.Set(NotificationOption.None)         // persist change
            } else {
                FieldEdit.NoChange                              // leave notify as-is
            }

        vm.applyEdits(
            task.id, TaskViewModel.TaskEdits(
                due = candidate.date.toFieldEdit(),             // Set(...) or Clear
                notify = notifyEdit
            )
        )

        // Update local UI state so the preview reflects the change immediately
        state.updateDue(candidate)
        if (clearingDue && state.currentNotifyOption != NotificationOption.None) {
            state.updateNotification(NotificationOption.None)
            state.clearErrors() // also clears notify error if it was shown
        } else {
            state.clearErrors()
        }
        state.exitEditMode()
    } else {
        state.setDueError(v)
    }
}

// Handles RECURRENCE changes coming from any RepeatChip
fun commitRecurrence(
    candidate: Recurrence, state: TaskEditState, task: Task, vm: TaskViewModel
) {
    val validation = validateRecurrenceDate(candidate, state.currentDueChoice)
    if (validation is RecurrenceValidationResult.Ok) {
        vm.applyEdits(
            task.id, TaskViewModel.TaskEdits(
                recurrence = FieldEdit.Set(candidate)
            )
        )
        state.updateRecurrence(candidate)
        state.clearErrors()
        state.exitEditMode()
    } else {
        state.setRecurrenceError(validation)
    }
}

fun commitNotification(
    opt: NotificationOption, state: TaskEditState, task: Task, vm: TaskViewModel
) {
    val validation = validateNotification(state.currentDueChoice, opt)
    if (validation is NotificationValidationResult.Ok) {

        // Only include 'due' if it's actually different from what's stored,
        // to avoid unnecessary DB writes.
        val dueEdit =
            if (state.currentDueChoice.date != task.due) state.currentDueChoice.date.toFieldEdit()
            else FieldEdit.NoChange

        vm.applyEdits(
            task.id, TaskViewModel.TaskEdits(
                due = dueEdit, notify = FieldEdit.Set(opt)
            )
        )

        state.updateNotification(opt)
        state.clearErrors()
        state.exitEditMode()
    } else {
        state.setNotifyError(validation)
    }
}