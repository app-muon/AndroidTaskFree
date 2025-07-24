package com.taskfree.app.domain.model

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.taskfree.app.R
import java.time.DayOfWeek
import java.time.LocalDate

@Composable
fun Recurrence.label(): String = when (this) {
    Recurrence.NONE -> stringResource(R.string.no_recurrence_label)
    Recurrence.DAILY -> stringResource(R.string.daily_recurrence)
    Recurrence.WEEKLY -> stringResource(R.string.weekly_recurrence)
    Recurrence.WEEKDAYS -> stringResource(R.string.recurrence_on_weekdays)
    Recurrence.WEEKENDS -> stringResource(R.string.recurrence_on_weekends)
    Recurrence.MONTHLY -> stringResource(R.string.monthly_recurrence)
}

private fun recurrenceNextDate(recurrence: Recurrence, baseDate: LocalDate): LocalDate? {
    return when (recurrence) {
        Recurrence.NONE -> null
        Recurrence.DAILY -> baseDate.plusDays(1)
        Recurrence.WEEKLY -> baseDate.plusDays(7)
        Recurrence.WEEKDAYS -> generateSequence(baseDate.plusDays(1)) { it.plusDays(1) }.first { it.dayOfWeek.value in 1..5 }
        Recurrence.WEEKENDS -> generateSequence(baseDate.plusDays(1)) { it.plusDays(1) }.first { it.dayOfWeek == DayOfWeek.SATURDAY || it.dayOfWeek == DayOfWeek.SUNDAY }
        // This single line correctly handles all the monthly recurrence cases,
        // including the day adjustment for months with fewer days than the original month.
        Recurrence.MONTHLY -> baseDate.plusMonths(1)
    }
}

fun Recurrence.calculateNextValidDueDate(baseDate: LocalDate): LocalDate? {

    // Keep jumping forward until we get a date that's at least tomorrow
    val tomorrow = LocalDate.now().plusDays(1)
    var next = baseDate
    Log.d(
        "Recurrence",
        "Calculating next due date from base: $baseDate, tomorrow is: $tomorrow"
    )

    repeat(1000) {
        next = recurrenceNextDate(this, next) ?: return null
        if (!next.isBefore(tomorrow)) return next
    }
    Log.d("Recurrence", "Too many iterations something has gone wrong")
    return null
}