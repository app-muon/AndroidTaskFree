// ui/admin/ToolsUiState.kt
package com.taskfree.app.ui.admin

data class ToolsUiState(
    val showArchived: Boolean = false
)

sealed interface ToolsEvent {
    data class Archived(val count: Int) : ToolsEvent
    object Deleted  : ToolsEvent
    object Failed : ToolsEvent
}
