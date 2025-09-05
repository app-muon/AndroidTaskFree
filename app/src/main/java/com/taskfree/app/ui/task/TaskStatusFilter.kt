// util/TaskStatusFilter.kt  (new file – 15 lines)
package com.taskfree.app.ui.task

import android.content.Context
import androidx.core.content.edit
import com.taskfree.app.domain.model.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

/**
 * One global source-of-truth for the “which statuses are visible?” set.
 * Lives outside every ViewModel, so everyone sees the same value.
 */
object TaskStatusFilter {
    @Volatile
    private var started = false
    private val _visible = MutableStateFlow(TaskStatus.entries.toSet())
    val visible: StateFlow<Set<TaskStatus>> = _visible

    fun toggle(status: TaskStatus) = _visible.update { vis ->
        if (status in vis) vis - status else vis + status
    }

    private const val PREFS = "task_prefs"
    private const val KEY_VISIBLE = "visible_statuses"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getStringSet(KEY_VISIBLE, null)?.let { names ->
            runCatching { _visible.value = names.map(TaskStatus::valueOf).toSet() }
        }
        // Save on every change
        visible.onEach { set ->
            prefs.edit {
                putStringSet(KEY_VISIBLE, set.map { it.name }.toSet())
            }
        }.launchIn(scope)
    }
}
