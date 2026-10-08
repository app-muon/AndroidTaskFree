// ui/admin/ToolsViewModel.kt
package com.taskfree.app.ui.admin

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskfree.app.data.repository.BackupManager
import com.taskfree.app.data.repository.CategoryRepository
import com.taskfree.app.data.repository.TaskRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
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

    private val _events = Channel<ToolsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /* ---------- toggles ---------- */
    fun toggleShowArchived() = _ui.update { it.copy(showArchived = !it.showArchived) }

    /* ---------- bulk actions ----- */
    fun archiveOldCompleted() =
        runOp { ToolsEvent.Archived(taskRepo.archiveTasksCompletedBeforeToday()) }

    fun archiveOldCompletedRepeats() =
        runOp { ToolsEvent.Archived(taskRepo.archiveRecurringCompletedBeforeToday()) }

    fun deleteArchived() = runOp { taskRepo.deleteAllArchivedTasks(); ToolsEvent.Deleted }

    /* helper */
    private fun runOp(block: suspend () -> ToolsEvent) =
        viewModelScope.launch(io) {
            val event = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("ToolsViewModel", "Bulk action failed", e)
                _events.send(ToolsEvent.Failed)
                return@launch
            }
            _events.send(event)
            _refresh.emit(Unit)
        }

    suspend fun buildBackup(): ByteArray =
        BackupManager.buildJson(catRepo, taskRepo)

    suspend fun importBackup(ctx: Context, uri: Uri) =
        BackupManager.import(ctx, uri, taskRepo)
}
