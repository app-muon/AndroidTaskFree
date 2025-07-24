// RecurrenceUIMapper.kt
package com.taskfree.app.ui.mapper

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.taskfree.app.R
import com.taskfree.app.domain.model.Recurrence
import com.taskfree.app.ui.components.DueChoice
import com.taskfree.app.ui.components.resultLabel


@StringRes
fun Recurrence.labelRes(): Int = when (this) {
    Recurrence.NONE -> R.string.no_string
    Recurrence.DAILY -> R.string.daily_recurrence
    Recurrence.WEEKLY -> R.string.weekly_recurrence
    Recurrence.WEEKDAYS -> R.string.recurrence_on_weekdays
    Recurrence.WEEKENDS -> R.string.recurrence_on_weekends
    Recurrence.MONTHLY -> R.string.monthly_recurrence
}

/**
 * UI helper – call **inside the Composable or View layer**.
 */
@Composable
fun Recurrence.displayName(): String = stringResource(labelRes())

/** Same idea for the “due + recurrence” line. */
@Composable
fun recurrenceLabel(dueChoice: DueChoice, recurrence: Recurrence): String {
    val base = dueChoice.resultLabel()
    return if (recurrence != Recurrence.NONE) "$base, ${recurrence.displayName()}"
    else base
}

