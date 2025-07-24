// ui/task/components/TaskFilterUtils.kt
package com.taskfree.app.ui.task.components

import com.taskfree.app.data.entities.TaskWithCategoryInfo
import com.taskfree.app.domain.model.TaskStatus
import com.taskfree.app.ui.components.SortMode
import java.time.LocalDate

object TaskFilterUtils {

    fun filterTasks(
        tasks: List<TaskWithCategoryInfo>, state: TaskListState, visibleStatuses: List<TaskStatus>
    ): List<TaskWithCategoryInfo> {
        return tasks.asSequence()
            .filter { state.selectedCategoryId == null || it.task.categoryId == state.selectedCategoryId }
            .filter { it.task.status in visibleStatuses }.filter { task ->
                val matchesSearch = state.debouncedSearch.isBlank() || task.task.text.contains(
                    state.debouncedSearch,
                    ignoreCase = true
                )
                matchesSearch
            }.toList()
    }

    fun sortTasks(
        tasks: List<TaskWithCategoryInfo>, sortMode: SortMode, orderProperty: OrderProperty
    ): List<TaskWithCategoryInfo> {
        return when (sortMode) {
            SortMode.USER -> tasks.sortedBy {
                when (orderProperty) {
                    OrderProperty.TODO_PAGE -> it.task.allCategoryPageOrder
                    OrderProperty.TASK_PAGE -> it.task.singleCategoryPageOrder
                }
            }

            SortMode.DATE_ASC -> tasks.sortedWith(compareBy<TaskWithCategoryInfo> {
                it.task.due ?: LocalDate.MAX
            }.thenBy { it.task.text.lowercase() })

            SortMode.DATE_DESC -> tasks.sortedWith(compareByDescending<TaskWithCategoryInfo> {
                it.task.due ?: LocalDate.MIN
            }.thenBy { it.task.text.lowercase() })
        }
    }
}