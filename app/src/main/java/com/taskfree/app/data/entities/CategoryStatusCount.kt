// CategoryStatusCount.kt
package com.taskfree.app.data.entities

import com.taskfree.app.domain.model.TaskStatus

data class CategoryStatusCount(
    val categoryId: Int,
    val status: TaskStatus,
    val count: Int
)
