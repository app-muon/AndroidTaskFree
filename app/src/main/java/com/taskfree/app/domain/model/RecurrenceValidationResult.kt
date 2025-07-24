// Recurrence
package com.taskfree.app.domain.model

import com.taskfree.app.ui.components.DueChoice
import com.taskfree.app.ui.components.DueKind
import com.taskfree.app.ui.components.kind
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter


sealed class RecurrenceValidationResult {
    data object Ok : RecurrenceValidationResult()
    data object MissingDueDate : RecurrenceValidationResult()
    data class NotWeekday(val date: String) : RecurrenceValidationResult()
    data class NotWeekend(val date: String) : RecurrenceValidationResult()
}

/**
 * Checks if a recurrence type is compatible with the given due date.
 *
 * @param recurrence The recurrence pattern to check
 * @param dueChoice The date to check for compatibility, as a DueChoice object
 * @return True if the combination is compatible, false otherwise
 */
fun validateRecurrenceDate(
    recurrence: Recurrence, dueChoice: DueChoice,
): RecurrenceValidationResult {

    if (dueChoice.kind == DueKind.NONE) {
        return if (recurrence == Recurrence.NONE) {
            RecurrenceValidationResult.Ok
        } else {
            RecurrenceValidationResult.MissingDueDate
        }
    }
    // Define the error message based on the invalid selection

    if (recurrence in listOf(
            Recurrence.NONE, Recurrence.DAILY, Recurrence.WEEKLY, Recurrence.MONTHLY
        )
    ) {
        return RecurrenceValidationResult.Ok
    }

    val dueDate = dueChoice.date ?: return RecurrenceValidationResult.MissingDueDate
    val dateFormatter = DateTimeFormatter.ofPattern("EEE, MMM d")
    val formatted = dueDate.format(dateFormatter)

    return when (recurrence) {
        Recurrence.WEEKDAYS -> if (dueDate.dayOfWeek.value in 1..5) RecurrenceValidationResult.Ok
        else RecurrenceValidationResult.NotWeekday(formatted)

        Recurrence.WEEKENDS -> if (dueDate.dayOfWeek in listOf(
                DayOfWeek.SATURDAY,
                DayOfWeek.SUNDAY
            )
        ) RecurrenceValidationResult.Ok
        else RecurrenceValidationResult.NotWeekend(formatted)

        else -> RecurrenceValidationResult.Ok
    }
}
