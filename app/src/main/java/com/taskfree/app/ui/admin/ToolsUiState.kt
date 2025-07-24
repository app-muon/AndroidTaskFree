// ui/admin/ToolsUiState.kt
package com.taskfree.app.ui.admin

data class ToolsUiState(
    val showArchived: Boolean = false,
    val lastEvent   : ToolsEvent? = null        // one-shot info for snackbars etc.
)

sealed interface ToolsEvent {
    object Archived : ToolsEvent
    object Deleted  : ToolsEvent
}
