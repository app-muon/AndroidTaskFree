// ui/category/CategoryUiState.kt
package com.taskfree.app.ui.category

import com.taskfree.app.data.entities.Category

data class CategoryUiState(
    val categories: List<Category> = emptyList(),
    val incompleteCounts: Map<Int, Int> = emptyMap(),
    val isDragging: Boolean = false,
    val isInitialLoadPending: Boolean = true,
    val error: Throwable? = null
)
