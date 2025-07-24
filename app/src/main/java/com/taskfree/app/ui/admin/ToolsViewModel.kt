// ui/admin/ToolsViewModel.kt
package com.taskfree.app.ui.admin

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskfree.app.data.repository.BackupManager
import com.taskfree.app.data.repository.CategoryRepository
import com.taskfree.app.data.repository.TaskRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ToolsViewModel(
    private val taskRepo: TaskRepository,
    private val catRepo: CategoryRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {
    private val _ui = MutableStateFlow(ToolsUiState())
    val uiState: StateFlow<ToolsUiState> = _ui.asStateFlow()

    private val _refresh = MutableSharedFlow<Unit>(
        replay = 0,            // fire-and-forget
        extraBufferCapacity = 1
    )
    val refresh = _refresh.asSharedFlow()

    /* ---------- toggles ---------- */
    fun toggleShowArchived() = _ui.update { it.copy(showArchived = !it.showArchived) }

    /* ---------- bulk actions ----- */
    fun archiveOldCompleted() =
        runOp { taskRepo.archiveTasksCompletedBeforeToday(); ToolsEvent.Archived }

    fun deleteArchived() = runOp { taskRepo.deleteAllArchivedTasks(); ToolsEvent.Deleted }

    /* helper */
    private fun runOp(block: suspend () -> ToolsEvent) =
        viewModelScope.launch(io) {
            val event = block()
            _ui.update { it.copy(lastEvent = event) }
            _refresh.emit(Unit)
        }

    suspend fun buildBackup(): ByteArray =
        BackupManager.buildJson(catRepo, taskRepo)

    suspend fun importBackup(ctx: Context, uri: Uri) =
        BackupManager.import(ctx, uri, taskRepo)
}
