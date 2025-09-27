// ui/task/TaskEditState.kt
package com.taskfree.app.ui.task

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import com.taskfree.app.data.entities.Category
import com.taskfree.app.domain.model.NotificationValidationResult
import com.taskfree.app.domain.model.Recurrence
import com.taskfree.app.domain.model.RecurrenceValidationResult
import com.taskfree.app.ui.components.DueChoice
import com.taskfree.app.ui.components.DueChoiceSaver
import com.taskfree.app.ui.components.NotificationOption
import com.taskfree.app.ui.components.NotificationOptionSaver
import com.taskfree.app.util.AppDateProvider

enum class EditingField { NONE, TITLE, DUE, NOTIFY, RECURRENCE, CATEGORY }


class TaskEditState(
    title: String = "",
    currentDueChoice: DueChoice = DueChoice.from(AppDateProvider.current.today()),
    currentNotifyOption: NotificationOption = NotificationOption.None,
    recurrence: Recurrence = Recurrence.NONE,
    selectedCategory: Category,
    editingField: EditingField = EditingField.NONE,
) {
    var title by mutableStateOf(title)
        private set
    var currentDueChoice by mutableStateOf(currentDueChoice)
        private set
    var currentNotifyOption by mutableStateOf(currentNotifyOption)
        private set
    var recurrence by mutableStateOf(recurrence)
        private set
    var selectedCategory by mutableStateOf(selectedCategory)
        private set
    var editingField by mutableStateOf(editingField)
        private set

    // Private backing state to avoid setter name clashes
    private var _dueError by mutableStateOf<RecurrenceValidationResult?>(null)
    val dueError: RecurrenceValidationResult? get() = _dueError

    private var _recurrenceError by mutableStateOf<RecurrenceValidationResult?>(null)
    val recurrenceError: RecurrenceValidationResult? get() = _recurrenceError

    private var _notifyError by mutableStateOf<NotificationValidationResult?>(null)
    val notifyError: NotificationValidationResult? get() = _notifyError

    fun clearErrors() {
        _dueError = null
        _recurrenceError = null
        _notifyError = null
    }

    fun setDueError(error: RecurrenceValidationResult) {
        _dueError = error
    }

    fun setRecurrenceError(error: RecurrenceValidationResult) {
        _recurrenceError = error
    }

    fun setNotifyError(error: NotificationValidationResult) {
        _notifyError = error
    }

    fun updateTitle(newTitle: String) {
        title = newTitle
    }

    fun updateDue(newDue: DueChoice) {
        currentDueChoice = newDue
    }

    fun updateNotification(opt: NotificationOption) {
        currentNotifyOption = opt
    }

    fun updateRecurrence(newRecurrence: Recurrence) {
        recurrence = newRecurrence
    }

    fun updateCategory(category: Category) {
        selectedCategory = category
    }

    fun enterEditMode(field: EditingField) {
        editingField = field
    }

    fun exitEditMode() {
        editingField = EditingField.NONE
    }
}

val TaskEditStateSaver = Saver<TaskEditState, Map<String, Any?>>(save = { state ->
    mapOf(
        "title" to state.title,
        "due" to with(DueChoiceSaver) { save(state.currentDueChoice) },
        "notify" to with(NotificationOptionSaver) { save(state.currentNotifyOption) }, // NEW
        "rec" to state.recurrence.name,
        "catId" to state.selectedCategory.id              // just the FK
    )
}, restore = { m ->
    TaskEditState(
        title = m["title"] as String,
        currentDueChoice = with(DueChoiceSaver) {
            restore(m["due"] as List<Any?>)!!
        },
        currentNotifyOption = with(NotificationOptionSaver) {        // NEW
            restore(m["notify"] as List<Any?>)!!
        },
        recurrence = Recurrence.valueOf(m["rec"] as String),
        selectedCategory = Category(id = m["catId"] as Int, title = "", color = 0)
    )
})

