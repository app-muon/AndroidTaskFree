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
import com.taskfree.app.domain.model.RepeatRule
import com.taskfree.app.ui.components.DueChoice
import com.taskfree.app.ui.components.DueChoiceSaver
import com.taskfree.app.ui.components.NotificationOption
import com.taskfree.app.ui.components.NotificationOptionSaver
import com.taskfree.app.util.AppDateProvider

enum class EditingField { NONE, TITLE, DUE, NOTIFY, CATEGORY }


class TaskEditState(
    title: String = "",
    currentDueChoice: DueChoice = DueChoice.from(AppDateProvider.current.today()),
    currentNotifyOption: NotificationOption = NotificationOption.None,
    repeatRule: RepeatRule? = RepeatRule(Recurrence.NONE),
    selectedCategory: Category,
    editingField: EditingField = EditingField.NONE,
) {
    var title by mutableStateOf(title)
        private set
    var currentDueChoice by mutableStateOf(currentDueChoice)
        private set
    var currentNotifyOption by mutableStateOf(currentNotifyOption)
        private set
    var repeatRule by mutableStateOf(repeatRule)
        private set
    var selectedCategory by mutableStateOf(selectedCategory)
        private set
    var editingField by mutableStateOf(editingField)
        private set

    // Private backing state to avoid setter name clashes
    private var _dueError by mutableStateOf<RecurrenceValidationResult?>(null)
    val dueError: RecurrenceValidationResult? get() = _dueError

    private var _notifyError by mutableStateOf<NotificationValidationResult?>(null)
    val notifyError: NotificationValidationResult? get() = _notifyError

    fun clearErrors() {
        _dueError = null
        _notifyError = null
    }

    fun setDueError(error: RecurrenceValidationResult) {
        _dueError = error
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

    fun updateRepeatRule(rule: RepeatRule?) {
        repeatRule = rule
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
        "rec" to state.repeatRule?.recurrence?.name,
        "interval" to state.repeatRule?.interval,
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
        repeatRule = (m["rec"] as? String)?.let { RepeatRule.fromStoredOrNull(Recurrence.valueOf(it), m["interval"] as? Int ?: 1) },
        selectedCategory = Category(id = m["catId"] as Int, title = "", color = 0)
    )
})

