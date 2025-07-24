// ui/task/TaskUiState.kt
package com.taskfree.app.ui.task

import com.taskfree.app.data.entities.TaskWithCategoryInfo
import com.taskfree.app.domain.model.TaskStatus
import com.taskfree.app.ui.task.components.TaskFilter

data class TaskUiState(
    val tasks: List<TaskWithCategoryInfo> = emptyList(),
    val visibleStatuses: Set<TaskStatus> = TaskStatus.entries.toSet(),
    val filter: TaskFilter = TaskFilter(),
    val isInitialLoadPending: Boolean = true,
    val error: Throwable? = null
)
