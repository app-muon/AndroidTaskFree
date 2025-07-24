// NewTaskDialog.kt
package com.taskfree.app.ui.task.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.taskfree.app.R
import com.taskfree.app.data.entities.Category
import com.taskfree.app.domain.model.Recurrence
import com.taskfree.app.domain.model.RecurrenceValidationResult
import com.taskfree.app.domain.model.validateNotification
import com.taskfree.app.domain.model.validateRecurrenceDate
import com.taskfree.app.ui.components.CategoryPill
import com.taskfree.app.ui.components.DueChoice
import com.taskfree.app.ui.components.DueChoiceSaver
import com.taskfree.app.ui.components.LabelledOptionPill
import com.taskfree.app.ui.components.NotificationOption
import com.taskfree.app.ui.components.NotificationOptionSaver
import com.taskfree.app.ui.components.RecurrencePill
import com.taskfree.app.ui.components.TaskFieldHeading
import com.taskfree.app.ui.components.PanelConstants
import com.taskfree.app.ui.components.choiceLabel
import com.taskfree.app.ui.components.isSameKindAs
import com.taskfree.app.ui.components.launchDatePicker
import com.taskfree.app.ui.components.launchTimePicker
import com.taskfree.app.ui.components.showDatePicker
import com.taskfree.app.ui.theme.outlinedFieldColours
import com.taskfree.app.ui.theme.providePanelColors
import showTimePicker

@Composable
fun NewTaskDialog(
    initialText: String,
    initialDue: DueChoice = DueChoice.fromSpecial(DueChoice.Special.NONE),
    initialRecurrence: Recurrence = Recurrence.NONE,
    initialCategory: Category,
    allCategories: List<Category>,
    onSave: (String, DueChoice, Recurrence, Int, NotificationOption) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialText) }
    var selectedDueChoice by rememberSaveable(stateSaver = DueChoiceSaver) {
        mutableStateOf(initialDue)
    }

    var selectedNotificationOption by rememberSaveable(stateSaver = NotificationOptionSaver) {
        mutableStateOf(NotificationOption.None)
    }
    val validationNotificationResult = remember(selectedDueChoice, selectedNotificationOption) {
        validateNotification(selectedDueChoice, selectedNotificationOption)
    }

    var recurrence by remember { mutableStateOf(initialRecurrence) }
    var selectedCategory by remember { mutableStateOf(initialCategory) }
    val focusRequester = remember { FocusRequester() }

    val validationRecurrenceResult = remember(selectedDueChoice, recurrence) {
        validateRecurrenceDate(recurrence, selectedDueChoice)
    }

    var pickTime by remember { mutableStateOf(false) }


    val keyboard = LocalSoftwareKeyboardController.current
    val colors = providePanelColors()

    LaunchedEffect(Unit) {
        if (text.isBlank()) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    var pickDate by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(pickDate) {
        if (pickDate) {
            showDatePicker(context = context,
                initialDate = selectedDueChoice.date,
                onDateSelected = { pickedDate ->
                    selectedDueChoice = DueChoice.from(pickedDate)
                })
            pickDate = false
        }
    }

    LaunchedEffect(pickTime) {
        if (pickTime) {
            showTimePicker(context = context,
                initial = selectedNotificationOption.time,
                onTimeSelected = {
                    selectedNotificationOption = NotificationOption.Other(it)
                })
            pickTime = false
        }
    }
    val isValidInput =
        text.isNotBlank() && validationRecurrenceResult is RecurrenceValidationResult.Ok

    Dialog(
        onDismissRequest = onDismiss, properties = DialogProperties(
            usePlatformDefaultWidth = false, dismissOnClickOutside = false
        )
    ) {
        Surface(
            color = colors.dialogBackground,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large
        ) {
            Column {
                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(R.string.add_task_button_label),
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ListItemDefaults.colors(
                        containerColor = colors.dialogBackground, headlineColor = colors.surfaceText
                    ),
                )
                HorizontalDivider(color = Color.Gray)
                Spacer(Modifier.height(PanelConstants.SECTION_VERTICAL_PADDING * 2))

                Column {
                    // Task Title Input
                    OutlinedTextField(
                        colors = colors.outlinedFieldColours(),
                        label = {
                            Text(stringResource(R.string.task_text_label))
                        },
                        modifier = Modifier
                            .focusRequester(focusRequester)
                            .fillMaxWidth()
                            .padding(horizontal = PanelConstants.HORIZONTAL_PADDING),
                        onValueChange = { text = it },
                        singleLine = true,
                        value = text
                    )

                    Spacer(Modifier.height(PanelConstants.SECTION_VERTICAL_PADDING * 2))
                    FlowRow(
                        modifier = Modifier.padding(horizontal = PanelConstants.HORIZONTAL_PADDING),
                        horizontalArrangement = Arrangement.spacedBy(PanelConstants.SPACER_WIDTH),
                        verticalArrangement = Arrangement.Center
                    ) {
                        TaskFieldHeading(
                            stringResource(R.string.category),
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                        allCategories.forEach { cat ->
                            val selected = cat == selectedCategory
                            CategoryPill(category = cat,
                                big = false,
                                selected = selected,
                                onClick = { selectedCategory = cat })
                        }
                    }

                    // Due Date Section
                    Spacer(Modifier.height(PanelConstants.SECTION_VERTICAL_PADDING * 2))
                    FlowRow(
                        modifier = Modifier.padding(horizontal = PanelConstants.HORIZONTAL_PADDING),
                        horizontalArrangement = Arrangement.spacedBy(PanelConstants.SPACER_WIDTH),
                        verticalArrangement = Arrangement.Center
                    ) {
                        TaskFieldHeading(
                            stringResource(R.string.due),
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                        DueChoice.allChoices().forEach { due ->
                            val selected = selectedDueChoice.isSameKindAs(due)
                            LabelledOptionPill(due.choiceLabel(),
                                big = false,
                                selected = selected,
                                onClick = {
                                    selectedDueChoice = due
                                    if (due.launchDatePicker()) {
                                        pickDate = true
                                    }
                                })
                        }
                    }

                    if (selectedDueChoice.launchDatePicker() && selectedDueChoice.date != null) {
                        Text(
                            stringResource(R.string.due_with_date, selectedDueChoice.date!!),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.surfaceText,
                            modifier = Modifier.padding(
                                top = PanelConstants.VERTICAL_PADDING,
                                start = PanelConstants.HORIZONTAL_PADDING
                            )
                        )
                    }

                    // Repeat Section
                    Spacer(Modifier.height(PanelConstants.SECTION_VERTICAL_PADDING * 2))
                    FlowRow(
                        modifier = Modifier.padding(horizontal = PanelConstants.HORIZONTAL_PADDING),
                        horizontalArrangement = Arrangement.spacedBy(PanelConstants.SPACER_WIDTH),
                        verticalArrangement = Arrangement.Center
                    ) {
                        TaskFieldHeading(
                            stringResource(R.string.repeat),
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )

                        Recurrence.entries.forEach { recurrenceValue ->
                            RecurrencePill(recurrence = recurrenceValue,
                                selected = recurrenceValue == recurrence,
                                onClick = {
                                    recurrence = recurrenceValue
                                })
                        }
                    }
                    // Validation Error Display
                    ValidationRecurrenceErrorText(validationRecurrenceResult)
                    ValidationNotificationErrorText(validationNotificationResult)

                    Spacer(Modifier.height(PanelConstants.SECTION_VERTICAL_PADDING * 2))
                    FlowRow(
                        modifier = Modifier.padding(horizontal = PanelConstants.HORIZONTAL_PADDING),
                        horizontalArrangement = Arrangement.spacedBy(PanelConstants.SPACER_WIDTH),
                        verticalArrangement = Arrangement.Center
                    ) {
                        TaskFieldHeading(
                            stringResource(R.string.notification_heading),
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                        NotificationOption.allChoices().forEach { opt ->
                            val selected = selectedNotificationOption::class == opt::class
                            LabelledOptionPill(label = opt.choiceLabel(),
                                big = false,
                                selected = selected,
                                onClick = {
                                    selectedNotificationOption = opt
                                    if (opt.launchTimePicker()) {
                                        pickTime = true
                                    }
                                })
                        }
                    }
                    // Optional label showing selected time
                    if (selectedNotificationOption is NotificationOption.Other && selectedNotificationOption.time != null) {
                        Text(
                            stringResource(
                                R.string.notification_time_with_time,
                                selectedNotificationOption.time.toString()
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.surfaceText,
                            modifier = Modifier.padding(
                                top = PanelConstants.VERTICAL_PADDING,
                                start = PanelConstants.HORIZONTAL_PADDING
                            )
                        )
                    }

                    // Action Buttons
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = PanelConstants.HORIZONTAL_PADDING,
                                vertical = PanelConstants.SECTION_VERTICAL_PADDING
                            ), horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = onDismiss, colors = ButtonDefaults.textButtonColors(
                                contentColor = colors.dialogButtonText
                            )
                        ) {
                            Text(stringResource(R.string.cancel_no_dialog_button))
                        }

                        TextButton(
                            onClick = {
                                if (isValidInput) {
                                    keyboard?.hide()
                                    onSave(
                                        text,
                                        selectedDueChoice,
                                        recurrence,
                                        selectedCategory.id,
                                        selectedNotificationOption
                                    )
                                }
                            }, enabled = isValidInput, colors = ButtonDefaults.textButtonColors(
                                contentColor = colors.dialogButtonText,
                                disabledContentColor = colors.dialogButtonText.copy(alpha = 0.5f)
                            )
                        ) {
                            Text(stringResource(R.string.save_yes_dialog_button))
                        }
                    }
                }
            }
        }
    }
}