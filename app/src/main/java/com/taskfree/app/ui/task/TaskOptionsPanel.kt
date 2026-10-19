// ui/task/TaskOptionsPanel.kt
package com.taskfree.app.ui.task

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
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
import com.taskfree.app.ui.components.CategoryPill
import com.taskfree.app.ui.components.DueChoice
import com.taskfree.app.ui.components.EditCancelRow
import com.taskfree.app.ui.components.IconOptionPill
import com.taskfree.app.ui.components.InfoPill
import com.taskfree.app.ui.components.LabelledOptionPill
import com.taskfree.app.ui.components.MetaRow
import com.taskfree.app.ui.components.NotificationOption
import com.taskfree.app.ui.components.PanelActionList
import com.taskfree.app.ui.components.PanelConstants
import com.taskfree.app.ui.components.RecurrencePill
import com.taskfree.app.ui.components.TaskDatesRow
import com.taskfree.app.ui.components.TaskFieldHeading
import com.taskfree.app.ui.components.TrailingEditIcon
import com.taskfree.app.ui.components.choiceLabel
import com.taskfree.app.ui.components.isSameKindAs
import com.taskfree.app.ui.components.launchDatePicker
import com.taskfree.app.ui.components.launchTimePicker
import com.taskfree.app.ui.components.linkified
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
            label = status.displayName(),
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
                label = stringResource(R.string.archive_task_yes_dialog_button),
                icon = Icons.Default.Archive,
                onClick = { onArchive(taskForLiveActions, ArchiveMode.Single) }),
            ActionItem(
                label = stringResource(R.string.archive_series_action),
                icon = Icons.Default.Archive,
                onClick = { onArchive(taskForLiveActions, ArchiveMode.Series) })
        )

        else -> listOf(
            ActionItem(
                label = stringResource(R.string.archive_task_yes_dialog_button),
                icon = Icons.Default.Archive,
                onClick = { onArchive(taskForLiveActions, ArchiveMode.Single) })
        )
    }

    /* ---------- clone, archive and delete: compact pills, delete last ---------- */
    val otherActions = listOfNotNull(
        if (categoryDeleted) null else ActionItem(
            label = "+ ${stringResource(R.string.clone_task_action)}",
            icon = Icons.Default.Add,
            onClick = { onClone(taskForLiveActions) }
        )
    ) + archiveActions + ActionItem(
        // Its confirmation says the task is deleted permanently.
        label = stringResource(R.string.delete_yes_dialog_button),
        icon = Icons.Default.Delete,
        iconTint = colors.brightRed,
        onClick = { onDeletePermanently(taskSnapshot) }
    )
    val otherActionsRow = ActionItem(
        onClick = {},
        dismissOnClick = false,
        labelContent = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(PanelConstants.CHIP_SPACING),
                verticalArrangement = Arrangement.spacedBy(PanelConstants.CHIP_SPACING)
            ) {
                otherActions.forEach { action ->
                    InfoPill(
                        title = action.labelText!!,
                        modifier = Modifier.semantics { role = Role.Button },
                        selectedFillColor = action.iconTint ?: colors.surfaceText,
                        unselectedFillColor = Color.Transparent,
                        unselectedTextColor = action.iconTint ?: colors.surfaceText,
                        border = true,
                        selected = false,
                        onClick = { taskVm.closeSheet { action.onClick() } }
                    )
                }
            }
        }
    )

    /* ---------- “go to category” arrow ---------- */
    val categoryExists = allCategories.any { it.id == currentCategory.id }
    val gotoCategoryEnabled = categoryExists && currentFilterCatId != currentCategory.id

    /* ---------- title, quick actions, editable attributes, notes and history ---------- */
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
                MetaRow(
                    colors = colors,
                    modifier = Modifier.testTag("task-title-row"),
                    headlineContent = { TaskFieldHeading("") },
                    supportingContent = {
                        if (sheet.active == TaskTextField.Title) {
                            TaskTextEditor(
                                value = sheet.title.value,
                                inputToken = sheet.title.inputToken,
                                field = TaskTextField.Title,
                                active = true,
                                limitReached = sheet.title.limitReached,
                                vm = taskVm,
                                onTextFocus = { editState.exitEditMode() }
                            )
                        } else {
                            TitleWithEditIcon(
                                title = sheet.title.value.text.trim().ifBlank { sheet.title.saved },
                                color = colors.surfaceText,
                                onEdit = { editState.exitEditMode(); taskVm.focusText(TaskTextField.Title) }
                            )
                        }
                    }
                )
                if (sheet.active == TaskTextField.Title) {
                    EditCancelRow(
                        onCancel = { taskVm.cancelTitleEdit(); focusManager.clearFocus() },
                        colors = colors
                    )
                }

                // These buttons apply immediately and close the sheet; the fields below open choices.
                HorizontalDivider(color = Color.Gray)
                Column(
                    Modifier.fillMaxWidth().padding(
                        horizontal = PanelConstants.HORIZONTAL_PADDING,
                        vertical = PanelConstants.SECTION_VERTICAL_PADDING
                    )
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(PanelConstants.CHIP_SPACING),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.DateRange, contentDescription = null, tint = colors.surfaceText)
                        Box(Modifier.weight(1f)) { quickDateAction.labelContent?.invoke() }
                    }
                    TaskFieldHeading(
                        stringResource(R.string.set_status),
                        Modifier.padding(top = 16.dp, bottom = PanelConstants.CHIP_SPACING)
                    )
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        // Keep labels readable on narrow screens and at larger text sizes.
                        val labelStyle = MaterialTheme.typography.bodyLarge
                        val textMeasurer = rememberTextMeasurer()
                        val labelWidth = with(LocalDensity.current) {
                            statusActions.maxOf {
                                textMeasurer.measure(it.labelText!!, style = labelStyle).size.width
                            }.toDp()
                        }
                        val minButtonWidth = maxOf(140.dp, labelWidth + 52.dp)
                        val columns = if (maxWidth >= minButtonWidth * 2 + PanelConstants.CHIP_SPACING) 2 else 1
                        Column(verticalArrangement = Arrangement.spacedBy(PanelConstants.CHIP_SPACING)) {
                            statusActions.chunked(columns).forEach { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                                    horizontalArrangement = Arrangement.spacedBy(PanelConstants.CHIP_SPACING)
                                ) {
                                    row.forEach { action ->
                                        val isCurrent = !action.enabled
                                        val statusColor = action.backgroundColour!!
                                        OutlinedButton(
                                            onClick = { taskVm.closeSheet { action.onClick() } },
                                            enabled = action.enabled,
                                            modifier = Modifier.weight(1f).heightIn(min = 56.dp).fillMaxHeight()
                                                .semantics { selected = isCurrent },
                                            shape = RoundedCornerShape(PanelConstants.CATEGORY_PILL_CORNER_RADIUS),
                                            border = BorderStroke(
                                                if (isCurrent) 2.dp else 1.dp,
                                                if (isCurrent) statusColor else statusColor.copy(alpha = 0.5f)
                                            ),
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                containerColor = statusColor.copy(alpha = 0.1f),
                                                contentColor = colors.surfaceText,
                                                disabledContainerColor = statusColor.copy(alpha = 0.22f),
                                                disabledContentColor = colors.surfaceText
                                            ),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
                                        ) {
                                            Row(
                                                Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(PanelConstants.CHIP_SPACING),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                                                    if (action.icon != null) {
                                                        Icon(action.icon, contentDescription = null)
                                                    } else {
                                                        Box(Modifier.size(10.dp).background(statusColor, CircleShape))
                                                    }
                                                }
                                                Text(action.labelText!!, style = labelStyle)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(bottom = PanelConstants.SECTION_VERTICAL_PADDING),
                    color = Color.Gray
                )

                /* --- CATEGORY ROW --- */
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MetaRow(
                        colors = colors,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = editState.editingField != EditingField.CATEGORY) {
                                focusManager.clearFocus(); taskVm.focusText(null); editState.enterEditMode(EditingField.CATEGORY)
                            },
                        headlineContent = { TaskFieldHeading(stringResource(R.string.category)) },
                        supportingContent = {
                            CategoryPill(
                                category = editState.selectedCategory, selected = true
                            )
                        }
                    )
                    // The arrow opens the category's tasks; it is hidden while choosing a category.
                    if (gotoCategoryEnabled && editState.editingField != EditingField.CATEGORY) {
                        IconButton(
                            onClick = { taskVm.closeSheet { onNavigateToCategory(currentCategory.id) } },
                            modifier = Modifier
                                .padding(end = PanelConstants.HORIZONTAL_PADDING)
                                .size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "${stringResource(R.string.go_to_category_action).trim()} ${currentCategory.title}",
                                tint = colors.surfaceText
                            )
                        }
                    }
                }

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

                /* --- DUE DATE ROW --- */
                MetaRow(
                    colors = colors,
                    modifier = Modifier.clickable(enabled = editState.editingField != EditingField.DUE) {
                        focusManager.clearFocus(); taskVm.focusText(null); editState.enterEditMode(EditingField.DUE)
                    },
                    headlineContent = { TaskFieldHeading(stringResource(R.string.due)) },
                    supportingContent = {
                        LabelledOptionPill(label = editState.currentDueChoice.resultLabel(), selected = true)
                    }
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

                /* --- RECURRENCE ROW --- */
                MetaRow(
                    colors = colors,
                    modifier = Modifier.clickable(enabled = editState.editingField != EditingField.RECURRENCE) {
                        focusManager.clearFocus(); taskVm.focusText(null); editState.enterEditMode(EditingField.RECURRENCE)
                    },
                    headlineContent = { TaskFieldHeading(stringResource(R.string.repeat)) },
                    supportingContent = {
                        LabelledOptionPill(
                            label = if (editState.recurrence == Recurrence.NONE)
                                stringResource(R.string.no_recurrence_label)
                            else editState.recurrence.displayName(),
                            selected = true
                        )
                    }
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

                /* --- NOTIFICATION ROW --- */
                MetaRow(
                    colors = colors,
                    modifier = Modifier.clickable(enabled = editState.editingField != EditingField.NOTIFY) {
                        focusManager.clearFocus(); taskVm.focusText(null); editState.enterEditMode(EditingField.NOTIFY)
                    },
                    headlineContent = { TaskFieldHeading(stringResource(R.string.notification_heading)) },
                    supportingContent = {
                        LabelledOptionPill(label = editState.currentNotifyOption.resultLabel(), selected = true)
                    }
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

                TaskNotesSection(sheet, taskVm, onTextFocus = { editState.exitEditMode() })

                /* --- CREATION AND COMPLETION DATES (read-only) --- */

                TaskDatesRow(taskSnapshot)
                HorizontalDivider(color = Color.Gray)
            }

        },
        actions = listOf(otherActionsRow),
        onDismiss = { dismiss() }
    )
}

/** The title with its pencil after the last word, so a title of several lines doesn't push the pencil onto a line of its own. */
@Composable
private fun TitleWithEditIcon(title: String, color: Color, onEdit: () -> Unit) {
    val linkStyle = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
    val text = remember(title, linkStyle) {
        buildAnnotatedString {
            append(linkified(title, linkStyle))
            appendInlineContent(EDIT_ICON_ID)
        }
    }
    // The size of TrailingEditIcon (an 8dp gap and a 24dp icon), whatever the text scale.
    val placeholder = with(LocalDensity.current) {
        Placeholder(32.dp.toSp(), 24.dp.toSp(), PlaceholderVerticalAlign.TextCenter)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = color,
        inlineContent = mapOf(EDIT_ICON_ID to InlineTextContent(placeholder) { TrailingEditIcon(onEdit) })
    )
}

private const val EDIT_ICON_ID = "edit"
