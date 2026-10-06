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

/**
 * Re-orders a visible slice of tasks and renumbers **all** order fields so they are
 * simple 0-based integers. No sparse gaps, no ×10 bodges.
 *
 * • allTasks   – full list (usually already fetched from DB)
 * • visible    – subset currently on screen (same objects as in allTasks)
 * • from / to  – indices within the *visible* list (sorted by order field)
 * • getOrder   – returns the order field you care about
 * • setOrder   – returns a *copy* of the task with a new order value
 *
 * Returns only the tasks whose order actually changed.
 */
fun computeReorderUpdates(
    allTasks: List<Task>,
    visible: List<Task>,
    from: Int,
    to: Int,
    getOrder: (Task) -> Int,
    setOrder: (Task, Int) -> Task,
): List<Task> {
    if (from == to) {
        return emptyList()
    }

    /* -------------------------------------------------------------
     * 1. Work on deterministic snapshots
     * ------------------------------------------------------------ */
    val fullSorted = allTasks.sortedBy(getOrder).toMutableList()
    val visibleSorted = visible.sortedBy(getOrder).toMutableList()
    val visibleIds = visibleSorted.map { it.id }.toSet()

    /* -------------------------------------------------------------
     * 2. Re-order the visible slice
     * ------------------------------------------------------------ */
    val moved = visibleSorted.removeAt(from)
    visibleSorted.add(to, moved)

    /* -------------------------------------------------------------
     * 3. Stitch the reordered slice back into the full list
     * ------------------------------------------------------------ */
    val itVis = visibleSorted.iterator()
    val newFull = fullSorted.map { if (it.id in visibleIds) itVis.next() else it }

    /* -------------------------------------------------------------
     * 4. Renumber every task (contiguous integers starting at 0)
     *    – touch only the ones whose order actually changed
     * ------------------------------------------------------------ */
    return newFull.mapIndexedNotNull { idx, task ->
        if (getOrder(task) != idx) setOrder(task, idx) else null
    }
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