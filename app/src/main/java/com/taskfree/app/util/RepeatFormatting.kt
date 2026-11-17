package com.taskfree.app.util

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes
import com.taskfree.app.R
import com.taskfree.app.domain.model.Recurrence
import com.taskfree.app.domain.model.RepeatRule

@StringRes
fun Recurrence.labelResId(): Int = when (this) {
    Recurrence.NONE -> R.string.repeat_none
    Recurrence.DAILY -> R.string.daily_recurrence
    Recurrence.WEEKLY -> R.string.weekly_recurrence
    Recurrence.WEEKDAYS -> R.string.recurrence_on_weekdays
    Recurrence.WEEKENDS -> R.string.recurrence_on_weekends
    Recurrence.MONTHLY -> R.string.monthly_recurrence
    Recurrence.QUARTERLY -> R.string.quarterly_recurrence
    Recurrence.YEARLY -> R.string.yearly_recurrence
}

fun RepeatRule?.format(context: Context): String = format(context.resources)

/** Android formatting shared by notifications and Compose, with tolerant display of bad data. */
fun RepeatRule?.format(resources: Resources): String {
    if (this == null || RepeatRule.fromStoredOrNull(recurrence, interval) == null) {
        return resources.getString(R.string.repeat_invalid)
    }
    if (interval == 1) return resources.getString(recurrence.labelResId())
    val resource = when (recurrence) {
        Recurrence.DAILY -> R.plurals.repeat_every_days
        Recurrence.WEEKLY -> R.plurals.repeat_every_weeks
        Recurrence.MONTHLY -> R.plurals.repeat_every_months
        else -> return resources.getString(R.string.repeat_invalid)
    }
    return resources.getQuantityString(resource, interval, interval)
}
