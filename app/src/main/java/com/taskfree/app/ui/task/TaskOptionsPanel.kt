// ui/task/TaskOptionsPanel.kt
package com.taskfree.app.ui.task

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskfree.app.R
import com.taskfree.app.data.entities.Task
import com.taskfree.app.data.entities.TaskWithCategoryInfo
import com.taskfree.app.domain.model.Recurrence
import com.taskfree.app.domain.model.TaskStatus
import com.taskfree.app.ui.category.CategoryViewModel
import com.taskfree.app.ui.category.CategoryVmFactory
import com.taskfree.app.ui.components.ActionItem
import com.taskfree.app.ui.components.AutoLinkedText
import com.taskfree.app.ui.components.CategoryPill
import com.taskfree.app.ui.components.DueChoice
import com.taskfree.app.ui.components.EditCancelRow
import com.taskfree.app.ui.components.EditableMetaRow
import com.taskfree.app.ui.components.IconOptionPill
import com.taskfree.app.ui.components.LabelledOptionPill
import com.taskfree.app.ui.components.NotificationOption
import com.taskfree.app.ui.components.PanelActionList
import com.taskfree.app.ui.components.PanelConstants
import com.taskfree.app.ui.components.RecurrencePill
import com.taskfree.app.ui.components.TaskDatesRow
import com.taskfree.app.ui.components.choiceLabel
import com.taskfree.app.ui.components.isSameKindAs
import com.taskfree.app.ui.components.launchDatePicker
import com.taskfree.app.ui.components.launchTimePicker
import com.taskfree.app.ui.components.resultLabel
import com.taskfree.app.ui.components.showDatePicker
import com.taskfree.app.ui.mapper.backgroundColor
import com.taskfree.app.ui.mapper.displayName
import com.taskfree.app.ui.task.components.ArchiveMode
import com.taskfree.app.ui.task.components.QuickDateKind
import com.taskfree.app.ui.task.components.ValidationNotificationErrorText
import com.taskfree.app.ui.task.components.ValidationRecurrenceErrorText
import com.taskfree.app.ui.task.components.commitDue
import com.taskfree.app.ui.task.components.commitNotification
import com.taskfree.app.ui.task.components.commitRecurrence
import com.taskfree.app.ui.task.components.quickDateKind
import com.taskfree.app.ui.theme.providePanelColors
import com.taskfree.app.util.AppDateProvider
import com.taskfree.app.util.weekdayShortLabel
import showTimePicker

@Composable
fun TaskOptionsPanel(
    task: TaskWithCategoryInfo?,
    taskVm: TaskViewModel,
    onNavigateToCategory: (Int) -> Unit,
    onArchive: (Task, ArchiveMode) -> Unit,
    onClone: (Task) -> Unit,
    onDeletePermanently: (Task) -> Unit,
    onDismiss: () -> Unit,
    currentFilterCatId: Int?
) {
    val taskWithCategory = task ?: return
    val taskSnapshot = taskWithCategory.task
    val category = taskWithCategory.category
    val colors = providePanelColors()
    val sheetState by taskVm.sheetState.collectAsState()
    val sheet = sheetState ?: return
    val focusManager = LocalFocusManager.current
    fun dismiss() { taskVm.closeSheet(onDismiss) }

    val context = LocalContext.current.applicationContext as android.app.Application
    val categoriesVm: CategoryViewModel = viewModel(factory = CategoryVmFactory(context))
    val pickerContext = LocalContext.current
    val uiState by categoriesVm.uiState.collectAsState()
    val allCategories = uiState.categories

    val currentDueChoice = taskSnapshot.due?.let { DueChoice.from(it) }
        ?: DueChoice.fromSpecial(DueChoice.Special.NONE)
    val currentNotifyOption = NotificationOption.fromTask(taskSnapshot)
    val kind = quickDateKind(taskSnapshot)
    fun applyPostponeDate(date: java.time.LocalDate) {
        taskVm.applyEdits(
            taskSnapshot.id,
            TaskViewModel.TaskEdits(due = FieldEdit.Set(date))
        )
    }
    fun showDueDatePicker(initialDate: java.time.LocalDate, minDate: java.time.LocalDate) {
        showDatePicker(
            context = pickerContext,
            initialDate = initialDate,
            minDate = minDate,
            onDateSelected = { picked ->
                applyPostponeDate(picked)
                dismiss()
            }
        )
    }

    val quickDateAction = when (kind) {
        QuickDateKind.POSTPONE -> ActionItem(
            icon = Icons.Default.DateRange,
            onClick = {},
            dismissOnClick = false,
            labelContent = {
                val today = AppDateProvider.current.today()
                val tomorrowLabel = stringResource(R.string.tomorrow)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(PanelConstants.CHIP_SPACING),
                    verticalArrangement = Arrangement.spacedBy(PanelConstants.SPACER_WIDTH),
                    itemVerticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.postpone_action),
                        color = colors.surfaceText,
                        modifier = Modifier.padding(end = PanelConstants.SPACER_WIDTH)
                    )
                    val tomorrow = today.plusDays(1)
                    (1L..3L).forEach { offset ->
                        val date = today.plusDays(offset)
                        val label = if (offset == 1L) {
                            tomorrowLabel
                        } else {
                            weekdayShortLabel(date)
                        }
                        LabelledOptionPill(
                            label = label,
                            onClick = {
                                applyPostponeDate(date)
                                dismiss()
                            }
                        )
                    }
                    IconOptionPill(
                        icon = Icons.Default.DateRange,
                        contentDescription = stringResource(R.string.date_picker),
                        onClick = { showDueDatePicker(initialDate = tomorrow, minDate = tomorrow) }
                    )
                }
            }
        )

        QuickDateKind.SET -> ActionItem(
            icon = Icons.Default.DateRange,
            onClick = {},
            dismissOnClick = false,
            labelContent = {
                val today = AppDateProvider.current.today()
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(PanelConstants.CHIP_SPACING),
                    verticalArrangement = Arrangement.spacedBy(PanelConstants.SPACER_WIDTH),
                    itemVerticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.set_due_action),
                        color = colors.surfaceText,
                        modifier = Modifier.padding(end = PanelConstants.SPACER_WIDTH)
                    )
                    LabelledOptionPill(
                        label = stringResource(R.string.today),
                        onClick = { applyPostponeDate(today); dismiss() }
                    )
                    LabelledOptionPill(
                        label = stringResource(R.string.tomorrow),
                        onClick = { applyPostponeDate(today.plusDays(1)); dismiss() }
                    )
                    LabelledOptionPill(
                        label = stringResource(R.string.offset_weeks_format, 1),
                        onClick = { applyPostponeDate(today.plusDays(7)); dismiss() }
                    )
                    IconOptionPill(
                        icon = Icons.Default.DateRange,
                        contentDescription = stringResource(R.string.date_picker),
                        onClick = { showDueDatePicker(initialDate = today, minDate = today) }
                    )
                }
            }
        )

        QuickDateKind.MOVE -> ActionItem(
            icon = Icons.Default.DateRange,
            onClick = {},
            dismissOnClick = false,
            labelContent = {
                val today = AppDateProvider.current.today()
                val due = taskSnapshot.due!!
                val tomorrow = today.plusDays(1)
                val moveDateOptions = generateSequence(today) { it.plusDays(1) }
                    .filter { it != due }
                    .take(3)
                    .toList()
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(PanelConstants.CHIP_SPACING),
                    verticalArrangement = Arrangement.spacedBy(PanelConstants.SPACER_WIDTH),
                    itemVerticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.move_action),
                        color = colors.surfaceText,
                        modifier = Modifier.padding(end = PanelConstants.SPACER_WIDTH)
                    )
                    moveDateOptions.forEach { date ->
                        val label = when (date) {
                            today -> stringResource(R.string.today)
                            tomorrow -> stringResource(R.string.tomorrow)
                            else -> weekdayShortLabel(date)
                        }
                        LabelledOptionPill(
                            label = label,
                            onClick = { applyPostponeDate(date); dismiss() }
                        )
                    }
                    IconOptionPill(
                        icon = Icons.Default.DateRange,
                        contentDescription = stringResource(R.string.date_picker),
                        onClick = { showDueDatePicker(initialDate = due, minDate = today) }
                    )
                }
            }
        )
    }

    val editState by rememberSaveable(stateSaver = TaskEditStateSaver) {
        mutableStateOf(
            TaskEditState(
                title = taskSnapshot.text,
                currentDueChoice = currentDueChoice,
                currentNotifyOption = currentNotifyOption,
                recurrence = taskSnapshot.recurrence,
                selectedCategory = allCategories.firstOrNull { it.id == taskSnapshot.categoryId }
                    ?: category
            )
        )
    }
    val currentCategory =
        allCategories.firstOrNull { it.id == editState.selectedCategory.id }
            ?: editState.selectedCategory
    val categoryDeleted = currentCategory.isDeleted
    val taskForLiveActions = taskSnapshot.copy(categoryId = currentCategory.id)

    /* ---------- status sub-menu ---------- */
    val statusActions = TaskStatus.entries.map { status ->
        val isCurrent = taskSnapshot.status == status
        ActionItem(
            label = if (isCurrent) {
                status.displayName()
            } else {

                stringResource(R.string.set_as_for_task_status, status.displayName())
            },
            enabled = !isCurrent,
            onClick = { taskVm.updateStatus(taskSnapshot.id, status); onDismiss() },
            icon = if (isCurrent) Icons.Default.Check else null,
            backgroundColour = status.backgroundColor(),
        )
    }

    /* ---------- archive / unarchive ---------- */
    val archiveActions = when {
        taskSnapshot.isArchived && !categoryDeleted -> listOf(
            ActionItem(
                label = stringResource(R.string.unarchive_task),
                icon = Icons.Default.Check,
                onClick = {
                    taskVm.unArchive(taskForLiveActions)
                    dismiss()
                })
        )

        taskSnapshot.isArchived -> emptyList()

        editState.recurrence != Recurrence.NONE -> listOf(
            ActionItem(
                label = stringResource(R.string.archive_task_action),
                icon = Icons.Default.Archive,
                iconTint = colors.darkRed,
                onClick = { onArchive(taskForLiveActions, ArchiveMode.Single) }),
            ActionItem(
                label = stringResource(R.string.archive_series_action),
                icon = Icons.Default.Archive,
                iconTint = colors.brightRed,
                onClick = { onArchive(taskForLiveActions, ArchiveMode.Series) })
        )

        else -> listOf(
            ActionItem(
                label = stringResource(R.string.archive_task_action),
                icon = Icons.Default.Archive,
                iconTint = colors.darkRed,
                onClick = { onArchive(taskForLiveActions, ArchiveMode.Single) })
        )
    }

    /* ---------- “go to category” pill ---------- */
    val categoryExists = allCategories.any { it.id == currentCategory.id }
    val gotoCategoryEnabled = categoryExists && currentFilterCatId != currentCategory.id
    val gotoCategoryAction =
        ActionItem(icon = Icons.AutoMirrored.Filled.List, enabled = gotoCategoryEnabled, onClick = {
            onNavigateToCategory(currentCategory.id)
        }, labelContent = {
            val alpha = if (gotoCategoryEnabled) 1f else 0.4f
            Row(
                verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(alpha)
            ) {
                Text(
                    text = stringResource(R.string.go_to_category_action),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.surfaceText,
                    modifier = Modifier.padding(end = 8.dp)
                )
                CategoryPill(
                    category = currentCategory, big = true, selected = true
                )
            }
        })

    /* ---------- build the sheet ---------- *//* ---------- editable metadata top section ---------- */
    PanelActionList(
        headerContent = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = PanelConstants.SECTION_VERTICAL_PADDING)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { focusManager.clearFocus(); taskVm.focusText(null) })
                    }
            ) {
                /* --- TITLE ROW --- */
                EditableMetaRow(
                    label = "",
                    modifier = Modifier.testTag("task-title-row"),
                    value = {
                        if (sheet.active == TaskTextField.Title) {
                            TaskTextEditor(
                                value = sheet.title.value,
                                field = TaskTextField.Title,
                                active = true,
                                limitReached = sheet.title.limitReached,
                                vm = taskVm,
                                onTextFocus = { editState.exitEditMode() }
                            )
                        } else {
                            AutoLinkedText(
                                raw = sheet.title.value.text.trim().ifBlank { sheet.title.saved },
                                style = MaterialTheme.typography.titleLarge,
                                color = colors.surfaceText
                            )
                        }
                    },
                    currentField = sheet.active == TaskTextField.Title,
                    onEdit = { editState.exitEditMode(); taskVm.focusText(TaskTextField.Title) },
                    colors = colors
                )

                /* --- DUE DATE ROW --- */
                EditableMetaRow(
                    colors = colors,
                    label = stringResource(R.string.due),
                    value = {
                        Text(
                            editState.currentDueChoice.resultLabel(),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    currentField = editState.editingField == EditingField.DUE,
                    onEdit = { focusManager.clearFocus(); taskVm.focusText(null); editState.enterEditMode(EditingField.DUE) }
                )

                if (editState.editingField == EditingField.DUE) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(PanelConstants.CHIP_SPACING),
                        modifier = Modifier.padding(
                            horizontal = PanelConstants.HORIZONTAL_PADDING,
                            vertical = PanelConstants.VERTICAL_PADDING
                        )
                    ) {
                        DueChoice.allChoices().forEach { choice ->
                            val isSelected = editState.currentDueChoice.isSameKindAs(choice)
                            LabelledOptionPill(
                                label = choice.choiceLabel(),
                                selected = isSelected,
                                error = editState.dueError != null && isSelected,
                                onClick = {
                                    if (choice.launchDatePicker()) {
                                        showDatePicker(
                                            context = pickerContext,

                                            initialDate = editState.currentDueChoice.date,
                                            onDateSelected = { picked ->
                                                commitDue(
                                                    DueChoice.from(picked),
                                                    editState,
                                                    taskSnapshot,
                                                    taskVm
                                                )
                                            }
                                        )
                                    } else {
                                        commitDue(choice, editState, taskSnapshot, taskVm)
                                    }
                                }
                            )
                        }
                    }
                    EditCancelRow(
                        onCancel = { editState.clearErrors(); editState.exitEditMode() },
                        colors = colors
                    )
                    if (editState.dueError != null) {
                        ValidationRecurrenceErrorText(editState.dueError!!)
                    }

                }

                /* --- NOTIFICATION ROW --- */
                EditableMetaRow(
                    colors = colors,
                    label = stringResource(R.string.notification_heading),
                    value = {
                        Text(
                            editState.currentNotifyOption.resultLabel(),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    currentField = editState.editingField == EditingField.NOTIFY,
                    onEdit = { focusManager.clearFocus(); taskVm.focusText(null); editState.enterEditMode(EditingField.NOTIFY) }
                )

                if (editState.editingField == EditingField.NOTIFY) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(PanelConstants.CHIP_SPACING),
                        modifier = Modifier.padding(
                            horizontal = PanelConstants.HORIZONTAL_PADDING,
                            vertical = PanelConstants.VERTICAL_PADDING
                        )
                    ) {
                        NotificationOption.allChoices().forEach { opt ->
                            val chosen = editState.currentNotifyOption.isSameKindAs(opt)
                            LabelledOptionPill(
                                label = opt.choiceLabel(),
                                selected = chosen,
                                error = editState.notifyError != null && chosen,
                                onClick = {
                                    if (opt.launchTimePicker()) {
                                        showTimePicker(
                                            context = pickerContext,
                                            initial = editState.currentNotifyOption.time
                                        ) { picked ->
                                            commitNotification(
                                                NotificationOption.Other(picked),
                                                editState,
                                                taskSnapshot,
                                                taskVm
                                            )
                                        }
                                    } else {
                                        commitNotification(
                                            opt,
                                            editState, taskSnapshot,
                                            taskVm
                                        )
                                    }
                                }
                            )
                        }
                    }
                    EditCancelRow(
                        onCancel = { editState.clearErrors(); editState.exitEditMode() },
                        colors = colors
                    )
                    if (editState.notifyError != null) {
                        ValidationNotificationErrorText(editState.notifyError!!)
                    }
                }

                /* --- RECURRENCE ROW --- */
                EditableMetaRow(
                    colors = colors,
                    label = stringResource(R.string.repeat),
                    value = {
                        Text(
                            if (editState.recurrence == Recurrence.NONE)
                                stringResource(R.string.no_recurrence_label)
                            else editState.recurrence.displayName(),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    currentField = editState.editingField == EditingField.RECURRENCE,
                    onEdit = { focusManager.clearFocus(); taskVm.focusText(null); editState.enterEditMode(EditingField.RECURRENCE) }
                )

                if (editState.editingField == EditingField.RECURRENCE) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(PanelConstants.CHIP_SPACING),
                        modifier = Modifier.padding(
                            horizontal = PanelConstants.HORIZONTAL_PADDING,
                            vertical = PanelConstants.VERTICAL_PADDING
                        )
                    ) {
                        Recurrence.entries.forEach { rec ->
                            val isSelected = rec == editState.recurrence
                            RecurrencePill(
                                recurrence = rec,
                                selected = isSelected,
                                error = editState.recurrenceError != null && isSelected,   // ✅ use flag
                                onClick = {
                                    commitRecurrence(rec, editState, taskSnapshot, taskVm)
                                }
                            )
                        }
                    }
                    EditCancelRow(
                        onCancel = { editState.clearErrors(); editState.exitEditMode() },
                        colors = colors,
                        modifier = Modifier.padding(end = PanelConstants.HORIZONTAL_PADDING)
                    )
                    if (editState.recurrenceError != null) {
                        ValidationRecurrenceErrorText(editState.recurrenceError!!)
                    }
                }
                EditableMetaRow(
                    colors = colors,
                    label = stringResource(R.string.category),
                    value = {
                        CategoryPill(
                            category = editState.selectedCategory, selected = true
                        )
                    },
                    currentField = editState.editingField == EditingField.CATEGORY,
                    onEdit = { focusManager.clearFocus(); taskVm.focusText(null); editState.enterEditMode(EditingField.CATEGORY) })

                if (editState.editingField == EditingField.CATEGORY) {
                    FlowRow(
                        modifier = Modifier.padding(
                            start = PanelConstants.HORIZONTAL_PADDING,
                            top = PanelConstants.VERTICAL_PADDING,
                            end = PanelConstants.HORIZONTAL_PADDING
                        )
                    ) {
                        allCategories.forEach { cat ->
                            val selected = cat == editState.selectedCategory
                            CategoryPill(category = cat, selected = selected, onClick = {
                                if (cat.id != editState.selectedCategory.id) {
                                    editState.updateCategory(cat)
                                    taskVm.applyEdits(
                                        taskSnapshot.id,
                                        TaskViewModel.TaskEdits(categoryId = FieldEdit.Set(cat.id))  // ← wrap in Set
                                    )
                                }
                                editState.exitEditMode()
                            })
                        }
                    }
                    EditCancelRow(
                        onCancel = { editState.exitEditMode() },
                        colors = colors,
                        modifier = Modifier.padding(end = PanelConstants.HORIZONTAL_PADDING)
                    )
                }

                TaskNotesSection(sheet, taskVm, onTextFocus = { editState.exitEditMode() })

                /* --- CREATION AND COMPLETION DATES (read-only) --- */

                TaskDatesRow(taskSnapshot)
                HorizontalDivider(color = Color.Gray)
            }

        }, actions = (listOf(quickDateAction) + statusActions + listOfNotNull(
            gotoCategoryAction,
            if (categoryDeleted) null else ActionItem(
                label = stringResource(R.string.clone_task_action),
                icon = Icons.Default.Add,
                onClick = { onClone(taskForLiveActions) }
            ),
            ActionItem(
                label = stringResource(R.string.delete_task_permanently_action),
                icon = Icons.Default.Delete,
                iconTint = colors.brightRed,
                onClick = { onDeletePermanently(taskSnapshot) }
            )
        ).plus(archiveActions).mapIndexed { i, action ->
            // A line separates the date and status changes from the task's other actions.
            if (i == 0) action.copy(dividerAbove = true) else action
        }).map { action ->
            if (!action.dismissOnClick) action else action.copy(
                dismissOnClick = false,
                onClick = { taskVm.closeSheet { action.onClick() } }
            )
        }, onDismiss = { dismiss() }
    )
}
