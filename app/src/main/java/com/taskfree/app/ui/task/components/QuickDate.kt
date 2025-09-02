// app/src/main/java/com/taskfree/app/ui/task/components/QuickDate.kt
package com.taskfree.app.ui.task.components

import androidx.annotation.StringRes
import com.taskfree.app.R
import com.taskfree.app.data.entities.Task
import com.taskfree.app.util.AppDateProvider
import com.taskfree.app.util.DateProvider
import java.time.LocalDate

enum class QuickDateKind { SET_TODAY, POSTPONE_TOMORROW }

/** Decide which quick action to show for this task. */
fun quickDateKind(
    task: Task,
    dp: DateProvider = AppDateProvider.current
): QuickDateKind {
    val due = task.due
    return if (dp.isPast(due) || dp.isToday(due)) {
        QuickDateKind.POSTPONE_TOMORROW
    } else {
        // includes null or future due
        QuickDateKind.SET_TODAY
    }
}

@StringRes
fun QuickDateKind.labelRes(): Int = when (this) {
    QuickDateKind.SET_TODAY -> R.string.make_due_today
    QuickDateKind.POSTPONE_TOMORROW -> R.string.postpone_to_tomorrow_action
}

/** Compute the target date for the quick action. */
fun quickDateTarget(
    task: Task,
    dp: DateProvider = AppDateProvider.current
): LocalDate {
    val today = dp.today()
    return when (quickDateKind(task, dp)) {
        QuickDateKind.SET_TODAY -> today
        QuickDateKind.POSTPONE_TOMORROW -> today.plusDays(1) // (typo fixed below)
    }
}
