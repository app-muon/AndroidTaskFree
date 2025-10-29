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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskfree.app.R
import com.taskfree.app.data.entities.Category
import com.taskfree.app.data.entities.Task
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
import com.taskfree.app.ui.components.LabelledOptionPill
import com.taskfree.app.ui.components.MetaRow
import com.taskfree.app.ui.components.NotificationOption
import com.taskfree.app.ui.components.PanelActionList
import com.taskfree.app.ui.components.PanelConstants
import com.taskfree.app.ui.components.RecurrencePill
import com.taskfree.app.ui.components.TaskFieldHeading
import com.taskfree.app.ui.components.choiceLabel
import com.taskfree.app.ui.components.isSameKindAs
import com.taskfree.app.ui.components.launchDatePicker
import com.taskfree.app.ui.components.launchTimePicker
import com.taskfree.app.ui.components.resultLabel
import com.taskfree.app.ui.components.showDatePicker
import com.taskfree.app.ui.components.specificDateLabel
import com.taskfree.app.ui.mapper.backgroundColor
import com.taskfree.app.ui.mapper.displayName
import com.taskfree.app.ui.task.components.ArchiveMode
import com.taskfree.app.ui.task.components.ValidationNotificationErrorText
import com.taskfree.app.ui.task.components.ValidationRecurrenceErrorText
import com.taskfree.app.ui.task.components.commitDue
import com.taskfree.app.ui.task.components.commitNotification
import com.taskfree.app.ui.task.components.commitRecurrence
import com.taskfree.app.ui.task.components.labelRes
import com.taskfree.app.ui.task.components.quickDateKind
import com.taskfree.app.ui.theme.outlinedFieldColours
import com.taskfree.app.ui.theme.providePanelColors
import showTimePicker

@Composable
fun TaskOptionsPanel(
    task: Task?,
    taskVm: TaskViewModel,
    onNavigateToCategory: (Int) -> Unit,
    onArchive: (Task, ArchiveMode) -> Unit,
    onQuickDate: (Task) -> Unit,
    onClone: (Task) -> Unit,
    onDismiss: () -> Unit,
    currentFilterCatId: Int?
) {
    val taskSnapshot = task ?: return
    val colors = providePanelColors()

    val context = LocalContext.current.applicationContext as android.app.Application
    val categoriesVm: CategoryViewModel = viewModel(factory = CategoryVmFactory(context))
    val pickerContext = LocalContext.current
    val uiState by categoriesVm.uiState.collectAsState()
    val allCategories = uiState.categories

    val currentDueChoice = taskSnapshot.due?.let { DueChoice.from(it) }
        ?: DueChoice.fromSpecial(DueChoice.Special.NONE)
    val currentNotifyOption = NotificationOption.fromTask(taskSnapshot)
    val kind = quickDateKind(taskSnapshot)
    val quickDateAction = ActionItem(
        label = stringResource(kind.labelRes()),
        icon = Icons.Default.DateRange,
        onClick = { onQuickDate(taskSnapshot) }
    )

    val editState by rememberSaveable(stateSaver = TaskEditStateSaver) {
        mutableStateOf(
            TaskEditState(
                title = taskSnapshot.text,
                currentDueChoice = currentDueChoice,
                currentNotifyOption = currentNotifyOption,
                recurrence = taskSnapshot.recurrence,
                selectedCategory = allCategories.firstOrNull { it.id == taskSnapshot.categoryId }
                    ?: allCategories.first()
            )
        )
    }

    val category: Category =
        allCategories.firstOrNull { it.id == taskSnapshot.categoryId } ?: Category(
            id = -1,
            title = stringResource(R.string.name_of_unknown_category),
            color = colorResource(R.color.pill_colour).value.toLong(),
            categoryPageOrder = -1
        )

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
            onClick = { taskVm.updateStatus(taskSnapshot, status) },
            icon = if (isCurrent) Icons.Default.Check else null,
            fontWeight = if (status == TaskStatus.DONE) FontWeight.Bold else null,
            backgroundColour = status.backgroundColor(),
        )
    }

    /* ---------- archive / unarchive ---------- */
    val archiveActions = when {
        taskSnapshot.isArchived -> listOf(
            ActionItem(
                label = stringResource(R.string.unarchive_task),
                icon = Icons.Default.Check,
                onClick = {
                    taskVm.unArchive(taskSnapshot)
                    onDismiss()
                })
        )

        taskSnapshot.recurrence != Recurrence.NONE -> listOf(
            ActionItem(
                label = stringResource(R.string.archive_task_action),
                icon = Icons.Default.Archive,
                iconTint = colors.darkRed,
                onClick = { onArchive(taskSnapshot, ArchiveMode.Single) }),
            ActionItem(
                label = stringResource(R.string.archive_series_action),
                icon = Icons.Default.Archive,
                iconTint = colors.brightRed,
                onClick = { onArchive(taskSnapshot, ArchiveMode.Series) })
        )

        else -> listOf(
            ActionItem(
                label = stringResource(R.string.archive_task_action),
                icon = Icons.Default.Archive,
                iconTint = colors.darkRed,
                onClick = { onArchive(taskSnapshot, ArchiveMode.Single) })
        )
    }

    /* ---------- “go to category” pill ---------- */
    val categoryExists = allCategories.any { it.id == taskSnapshot.categoryId }
    val gotoCategoryEnabled = categoryExists && currentFilterCatId != taskSnapshot.categoryId
    val gotoCategoryAction =
        ActionItem(icon = Icons.AutoMirrored.Filled.List, enabled = gotoCategoryEnabled, onClick = {
            onDismiss()
            onNavigateToCategory(taskSnapshot.categoryId)
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
                    category = category, big = true, selected = true
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
            ) {
                /* --- TITLE ROW --- */
                EditableMetaRow(
                    label = "",
                    value = {
                        if (editState.editingField == EditingField.TITLE) {
                            var newTitle by rememberSaveable { mutableStateOf(editState.title) }

                            Column {
                                OutlinedTextField(
                                    value = newTitle,
                                    onValueChange = { newTitle = it },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = colors.outlinedFieldColours()
                                )
                                EditCancelRow(
                                    onCancel = { editState.exitEditMode() },
                                    onSave = {
                                        val t = newTitle.trim()
                                        if (t.isNotEmpty() && t != editState.title) {
                                            taskVm.applyEdits(
                                                taskSnapshot.id,
                                                TaskViewModel.TaskEdits(title = FieldEdit.Set(t))  // ← wrap in Set
                                            )
                                            editState.updateTitle(t)
                                        }
                                        editState.exitEditMode()
                                    },
                                    saveEnabled = newTitle.isNotBlank(),
                                    colors = colors
                                )
                            }
                        } else {
                            AutoLinkedText(
                                raw = editState.title,
                                style = MaterialTheme.typography.titleLarge,
                                color = colors.surfaceText
                            )
                        }
                    },
                    currentField = editState.editingField == EditingField.TITLE,
                    onEdit = { editState.enterEditMode(EditingField.TITLE) },
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
                    onEdit = { editState.enterEditMode(EditingField.DUE) }
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
                    onEdit = { editState.enterEditMode(EditingField.NOTIFY) }
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
                    onEdit = { editState.enterEditMode(EditingField.RECURRENCE) }
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
                    onEdit = { editState.enterEditMode(EditingField.CATEGORY) })

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
                }/* --- COMPLETION DATE ROW (read-only) --- */

                val content = if (taskSnapshot.completedDate != null) {
                    specificDateLabel(taskSnapshot.completedDate, context)
                } else {
                    stringResource(R.string.completed_no_label)
                }
                MetaRow(
                    headlineContent = {
                        TaskFieldHeading(stringResource(R.string.completed_date_label).uppercase())
                    }, supportingContent = { Text(content, style = MaterialTheme.typography.bodyMedium) }, colors = colors
                )
                HorizontalDivider(color = Color.Gray)
            }

        }, actions = listOf(
            quickDateAction,
            ActionItem(
                label = stringResource(R.string.clone_task_action),
                icon = Icons.Default.Add,
                onClick = { onClone(taskSnapshot) }
            )
        ) + archiveActions + gotoCategoryAction + statusActions, onDismiss = onDismiss
    )
}
