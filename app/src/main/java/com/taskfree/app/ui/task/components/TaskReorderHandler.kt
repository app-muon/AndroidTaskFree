// ui/task/components/TaskReorderHandler.kt
package com.taskfree.app.ui.task.components

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.taskfree.app.data.entities.Task
import com.taskfree.app.data.entities.TaskWithCategoryInfo
import com.taskfree.app.ui.task.TaskViewModel

fun mergeFullWithVisible(
    fullTasks: List<Task>,
    visibleTasks: List<Task>
): List<Task> {
    val visibleById = visibleTasks.associateBy { it.id }
    return fullTasks.map { visibleById[it.id] ?: it }
}

@Composable
fun rememberTaskReorderHandler(
    taskVm: TaskViewModel, orderProperty: OrderProperty, initialCategoryId: Int?
): (from: Int, to: Int, sortedFiltered: List<TaskWithCategoryInfo>, allUnfilteredTasks: List<TaskWithCategoryInfo>, onComplete: (() -> Unit)?) -> Unit {
    return remember(taskVm, orderProperty, initialCategoryId) {
        { from, to, sortedFiltered, allUnfilteredTasks, onComplete ->
            if (from >= 0 && to >= 0 && from < sortedFiltered.size && to < sortedFiltered.size) {
                val visibleTasks = sortedFiltered.map { it.task }
                Log.d("Handler",
                    "visible=${visibleTasks.map { it.id }}, "
                            + "full=${allUnfilteredTasks.map { it.task.id }}")

                if (initialCategoryId == null) {
                    val fullTasks =
                        allUnfilteredTasks.sortedBy { it.task.allCategoryPageOrder }.map { it.task }
                    val merged = mergeFullWithVisible(fullTasks, visibleTasks)
                    taskVm.moveInAllCategoryPage(merged, visibleTasks, from, to, onComplete)
                } else {
                    val fullCategoryTasks =
                        allUnfilteredTasks.filter { it.task.categoryId == initialCategoryId }
                            .sortedBy { it.task.singleCategoryPageOrder }.map { it.task }
                    val merged = mergeFullWithVisible(fullCategoryTasks, visibleTasks)
                    taskVm.moveInSingleCategoryPage(
                        full = merged,
                        visible = visibleTasks,
                        from = from,
                        to = to,
                        onComplete = onComplete
                    )
                }
            } else {
                onComplete?.invoke()
            }
        }
    }
}