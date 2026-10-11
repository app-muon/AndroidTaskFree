// ui/category/CategoryUiState.kt
package com.taskfree.app.ui.category

import com.taskfree.app.data.entities.Category
import com.taskfree.app.domain.model.TaskStatus

data class CategoryUiState(
    val categories: List<Category> = emptyList(),
    val statusCounts: Map<Int, Map<TaskStatus, Int>> = emptyMap(),
    val isDragging: Boolean = false,
    val isInitialLoadPending: Boolean = true,
    val error: Throwable? = null
)
