// ui/task/TaskFilter.kt
package com.taskfree.app.ui.task.components

import java.time.LocalDate

data class TaskFilter(
    val date        : LocalDate? = null,   // null = “all dates”
    val showArchived: Boolean     = false,
    val version: Int = 0
)
