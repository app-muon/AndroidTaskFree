// ui/task/components/TaskListState.kt
package com.taskfree.app.ui.task.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import com.taskfree.app.ui.components.DueChoice
import com.taskfree.app.ui.components.SortMode
import com.taskfree.app.ui.components.kind
import java.time.LocalDate

@Composable
fun rememberTaskListState(
    initial: TaskListState = TaskListState()
): MutableState<TaskListState> = rememberSaveable(stateSaver = TaskListState.Saver) {
    mutableStateOf(initial)
}

data class TaskListState(
    val selectedCategoryId: Int? = null,
    val searchText: String = "",
    val searchVisible: Boolean = false,
    val dueChoice: DueChoice = DueChoice.fromSpecial(DueChoice.Special.ALL),
    val sortMode: SortMode = SortMode.USER
) {
    val targetDate: LocalDate? = dueChoice.date
    val debouncedSearch: String get() = searchText.trim().lowercase()

    companion object {
        val Saver: Saver<TaskListState, List<Any?>> = Saver(
            save = { state ->
                listOf(
                    state.selectedCategoryId,
                    state.searchText,
                    state.searchVisible,
                    // Save DueChoice using the same logic as DueChoiceSaver
                    listOf(
                        state.dueChoice.kind.name,
                        state.dueChoice.date?.toString()
                    ),
                    state.sortMode.name
                )
            },
            restore = { saved ->
                try {
                    // Restore DueChoice using the same logic as DueChoiceSaver
                    val dueChoiceData = saved[3] as List<*>
                    val kindName = dueChoiceData[0] as String
                    val iso = dueChoiceData[1] as String?

                    val restoredDueChoice = when (kindName) {
                        "NONE" -> DueChoice.None
                        "ALL" -> DueChoice.All
                        "TODAY" -> DueChoice.Today
                        "TOMORROW" -> DueChoice.Tomorrow
                        "PLUS2" -> DueChoice.Plus2
                        "OTHER" -> DueChoice.Other(iso?.let(LocalDate::parse))
                        else -> DueChoice.None // Changed to match DueChoiceSaver fallback
                    }

                    TaskListState(
                        selectedCategoryId = saved[0] as Int?,
                        searchText = saved[1] as String,
                        searchVisible = saved[2] as Boolean,
                        dueChoice = restoredDueChoice,
                        sortMode = SortMode.valueOf(saved[4] as String)
                    )
                } catch (e: Exception) {
                    // If restoration fails, return default state
                    TaskListState()
                }
            }
        )
    }
}