package com.taskfree.app.ui.task.components

import com.taskfree.app.data.entities.Category
import com.taskfree.app.data.entities.TaskWithCategoryInfo
import com.taskfree.app.ui.components.DueChoice

sealed interface TaskDialogs {
    data object None : TaskDialogs
    data class Add(val category: Category, val dueChoice: DueChoice) : TaskDialogs
    data class Options(val task: TaskWithCategoryInfo) : TaskDialogs
    data class ConfirmArchive(val task: TaskWithCategoryInfo, val mode: ArchiveMode) : TaskDialogs
}
