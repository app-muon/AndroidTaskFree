// util/TaskStatusFilter.kt  (new file – 15 lines)
package com.taskfree.app.ui.task

import com.taskfree.app.domain.model.TaskStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * One global source-of-truth for the “which statuses are visible?” set.
 * Lives outside every ViewModel, so everyone sees the same value.
 */
object TaskStatusFilter {

    private val _visible = MutableStateFlow(TaskStatus.entries.toSet())
    val visible: StateFlow<Set<TaskStatus>> = _visible

    fun toggle(status: TaskStatus) = _visible.update { vis ->
        if (status in vis) vis - status else vis + status
    }
}
