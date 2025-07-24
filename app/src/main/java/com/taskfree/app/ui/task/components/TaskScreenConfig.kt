// ui/task/components/TaskScreenConfig.kt
package com.taskfree.app.ui.task.components

import com.taskfree.app.ui.components.DueChoice

data class TaskScreenConfig(
    val categoryId: Int? = null,
    val dueChoice: DueChoice = DueChoice.fromSpecial(DueChoice.Special.ALL)
)