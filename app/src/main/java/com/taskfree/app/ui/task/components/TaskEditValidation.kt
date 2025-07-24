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
import com.taskfree.app.ui.task.TaskEditState
import com.taskfree.app.ui.task.TaskViewModel
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
                start = PanelConstants.HORIZONTAL_PADDING,
                top = PanelConstants.ERROR_TOP_PADDING
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
                start = PanelConstants.HORIZONTAL_PADDING,
                top = PanelConstants.ERROR_TOP_PADDING
            )
        )
    }
}

// Handles DATE changes coming from any DateChip, incl. date-picker callback
fun commitDue(
    candidate: DueChoice,
    errorType: String,
    state: TaskEditState, task: Task, vm: TaskViewModel
) {
    when (validateRecurrenceDate(state.recurrence, candidate)) {
        is RecurrenceValidationResult.Ok -> {
            vm.updateDue(task, candidate)
            state.updateDue(candidate)
            state.clearErrors()
            state.exitEditMode()
        }

        else -> {
            when (errorType) {
                "date" -> state.setDueError()
                "recurrence" -> state.setRecurrenceError()
            }
        }
    }
}

// Handles RECURRENCE changes coming from any RepeatChip
fun commitRecurrence(
    candidate: Recurrence,
    state: TaskEditState,
    task: Task,
    vm: TaskViewModel
) {
    when (validateRecurrenceDate(candidate, state.currentDueChoice)) {
        is RecurrenceValidationResult.Ok -> {
            vm.updateRecurrence(task, candidate)
            state.updateRecurrence(candidate)
            state.clearErrors()
            state.exitEditMode()
        }

        else -> {
            state.setRecurrenceError()
        }
    }
}

fun commitNotification(
    opt: NotificationOption,
    editState: TaskEditState,
    task: Task,
    vm: TaskViewModel
) {
    val validation = validateNotification(editState.currentDueChoice, opt)
    if (validation is NotificationValidationResult.Ok) {
        editState.updateNotification(opt)
        vm.updateNotification(task, opt)
        editState.exitEditMode()
    } else {
        editState.setNotifyError() // new setter like dueError
    }
}