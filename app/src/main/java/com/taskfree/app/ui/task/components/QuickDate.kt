// app/src/main/java/com/taskfree/app/ui/task/components/QuickDate.kt
package com.taskfree.app.ui.task.components

import com.taskfree.app.data.entities.Task
import com.taskfree.app.util.AppDateProvider
import com.taskfree.app.util.DateProvider

enum class QuickDateKind { SET, POSTPONE, MOVE }

/** Decide which quick action to show for this task. */
fun quickDateKind(
    task: Task,
    dp: DateProvider = AppDateProvider.current
): QuickDateKind {
    val due = task.due
    return when {
        due == null -> QuickDateKind.SET
        dp.isPast(due) || dp.isToday(due) -> QuickDateKind.POSTPONE
        else -> QuickDateKind.MOVE
    }
}
