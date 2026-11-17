package com.taskfree.app.domain.model

import java.time.LocalDate

sealed class RecurrenceValidationResult {
    data object Ok : RecurrenceValidationResult()
    data object MissingDueDate : RecurrenceValidationResult()
    data object InvalidRule : RecurrenceValidationResult()
    data class NotWeekday(val date: LocalDate) : RecurrenceValidationResult()
    data class NotWeekend(val date: LocalDate) : RecurrenceValidationResult()
}

fun validateRecurrenceDate(rule: RepeatRule?, dueDate: LocalDate?): RecurrenceValidationResult {
    if (rule == null) return RecurrenceValidationResult.InvalidRule
    if (rule.recurrence == Recurrence.NONE) return RecurrenceValidationResult.Ok
    if (dueDate == null) return RecurrenceValidationResult.MissingDueDate
    return when {
        rule.recurrence == Recurrence.WEEKDAYS && dueDate.dayOfWeek.value > 5 ->
            RecurrenceValidationResult.NotWeekday(dueDate)
        rule.recurrence == Recurrence.WEEKENDS && dueDate.dayOfWeek.value < 6 ->
            RecurrenceValidationResult.NotWeekend(dueDate)
        else -> RecurrenceValidationResult.Ok
    }
}
