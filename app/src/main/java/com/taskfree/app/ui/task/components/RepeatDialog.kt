package com.taskfree.app.ui.task.components

import android.util.Log
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.taskfree.app.R
import com.taskfree.app.data.entities.Task
import com.taskfree.app.data.entities.repeatRuleOrNull
import com.taskfree.app.domain.model.Recurrence
import com.taskfree.app.domain.model.RecurrenceValidationResult
import com.taskfree.app.domain.model.RepeatRule
import com.taskfree.app.domain.model.TaskEditFailureReason
import com.taskfree.app.domain.model.baseDateAfterEdit
import com.taskfree.app.domain.model.calculateNextValidDueDate
import com.taskfree.app.domain.model.supportsInterval
import com.taskfree.app.domain.model.validateRecurrenceDate
import com.taskfree.app.ui.components.dialogMaxHeight
import com.taskfree.app.ui.components.dialogResponsiveWidth
import com.taskfree.app.ui.components.showDatePicker
import com.taskfree.app.ui.mapper.displayName
import com.taskfree.app.ui.mapper.messageResId
import com.taskfree.app.ui.task.TaskViewModel
import com.taskfree.app.ui.theme.providePanelColors
import com.taskfree.app.util.AppDateProvider
import com.taskfree.app.util.labelResId
import com.taskfree.app.util.weekdayShortLabel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Updates relative dates both at midnight and when the application returns to the foreground. */
@Composable
internal fun rememberRepeatToday(): LocalDate {
    var today by remember { mutableStateOf(AppDateProvider.current.today()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) today = AppDateProvider.current.today()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            today = AppDateProvider.current.today()
            delay(30_000)
        }
    }
    return today
}

@Composable
fun RepeatDialog(
    initialRule: RepeatRule,
    initialDue: LocalDate?,
    onDone: (RepeatRule, LocalDate?) -> Unit,
    onDismiss: () -> Unit,
    taskTitle: String? = null,
    saving: Boolean = false,
    saveFailed: Boolean = false,
    readOnly: Boolean = false,
    onEditCurrent: (() -> Unit)? = null,
    initialBaseDate: LocalDate? = initialDue,
    blockedReason: TaskEditFailureReason? = null,
    failureReason: TaskEditFailureReason? = null,
    invalidStoredRule: Boolean = false
) {
    var frequencyName by rememberSaveable { mutableStateOf(initialRule.recurrence.name) }
    var draftInterval by rememberSaveable { mutableIntStateOf(initialRule.interval) }
    var dueIso by rememberSaveable { mutableStateOf(initialDue?.toString()) }
    var defaultToday by rememberSaveable { mutableStateOf(false) }
    var automaticDate by rememberSaveable { mutableStateOf(false) }
    var dateControlsUsed by rememberSaveable { mutableStateOf(false) }
    val blocked = blockedReason != null
    val editable = !readOnly && !blocked && !saving
    val today = rememberRepeatToday()
    val frequency = if (readOnly) initialRule.recurrence else Recurrence.valueOf(frequencyName)
    val due = if (readOnly) initialDue else if (defaultToday) today else dueIso?.let(LocalDate::parse)
    val interval = if (readOnly) initialRule.interval else if (frequency.supportsInterval) draftInterval else 1
    val rule = RepeatRule(frequency, interval)
    val validation = validateRecurrenceDate(rule, due)
    val valid = validation == RecurrenceValidationResult.Ok
    val base = rule.baseDateAfterEdit(due, initialRule.recurrence, initialDue, initialBaseDate)
    val preview = if (valid && base != null && !readOnly && !blocked)
        rule.calculateNextValidDueDate(base, today) else null
    // Keep date correction available for the rest of this draft once its original date is incompatible.
    val showDuePrompt = !readOnly && (dateControlsUsed || blocked ||
        (frequency != Recurrence.NONE && (initialDue == null || !valid ||
            validateRecurrenceDate(rule, initialDue) != RecurrenceValidationResult.Ok)))
    val ruleLabel = if (readOnly && invalidStoredRule) stringResource(R.string.repeat_invalid) else rule.displayName()
    var settledRuleLabel by remember { mutableStateOf(ruleLabel) }
    LaunchedEffect(ruleLabel) {
        delay(250)
        settledRuleLabel = ruleLabel
    }
    val context = LocalContext.current
    val colors = providePanelColors()
    val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(LocalConfiguration.current.locales[0])
    // One format for every date, so the label doesn't change shape as the date moves.
    fun dateLabel(date: LocalDate) = "${weekdayShortLabel(date)} · ${date.format(formatter)}"

    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !saving,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier.dialogResponsiveWidth().dialogMaxHeight().testTag("repeat_dialog"),
            shape = MaterialTheme.shapes.large,
            color = colors.dialogBackground
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.repeat), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics {
                        stateDescription = settledRuleLabel
                        liveRegion = LiveRegionMode.Polite
                    })
                taskTitle?.let {
                    Text(stringResource(if (readOnly) R.string.repeat_history_for else R.string.repeat_editing_for, it),
                        style = MaterialTheme.typography.bodyLarge)
                }
                if (invalidStoredRule) Text(stringResource(if (readOnly) R.string.repeat_invalid
                    else R.string.repeat_invalid_repair), color = MaterialTheme.colorScheme.error)
                if (!(readOnly && invalidStoredRule)) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Recurrence.NONE, Recurrence.DAILY, Recurrence.WEEKLY, Recurrence.MONTHLY,
                        Recurrence.WEEKDAYS, Recurrence.WEEKENDS, Recurrence.QUARTERLY, Recurrence.YEARLY).forEach { rec ->
                        FilterChip(
                            selected = rec == frequency,
                            enabled = editable,
                            onClick = {
                                frequencyName = rec.name
                                if (rec == Recurrence.NONE && automaticDate) {
                                    dueIso = initialDue?.toString()
                                    defaultToday = false
                                    automaticDate = false
                                } else if (rec != Recurrence.NONE && due == null) {
                                    defaultToday = true
                                    automaticDate = true
                                }
                            },
                            label = {
                                Text(stringResource(when (rec) {
                                    Recurrence.DAILY -> R.string.repeat_days
                                    Recurrence.WEEKLY -> R.string.repeat_weeks
                                    Recurrence.MONTHLY -> R.string.repeat_months
                                    else -> rec.labelResId()
                                }))
                            }
                        )
                    }
                }
                if (frequency.supportsInterval) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        itemVerticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.repeat_every))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RepeatIntervalButton(
                                icon = Icons.Default.Remove,
                                label = stringResource(R.string.repeat_decrease_interval),
                                value = interval,
                                longClickLabel = stringResource(R.string.repeat_decrease_repeatedly),
                                tag = "repeat_decrement",
                                enabled = editable && interval > 1,
                                onStep = { draftInterval = (draftInterval - 1).coerceAtLeast(1) }
                            )
                            Text(
                                interval.toString(),
                                style = MaterialTheme.typography.titleLarge,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.widthIn(min = 48.dp).testTag("repeat_interval")
                            )
                            RepeatIntervalButton(
                                icon = Icons.Default.Add,
                                label = stringResource(R.string.repeat_increase_interval),
                                value = interval,
                                longClickLabel = stringResource(R.string.repeat_increase_repeatedly),
                                tag = "repeat_increment",
                                enabled = editable && interval < 99,
                                onStep = { draftInterval = (draftInterval + 1).coerceAtMost(99) }
                            )
                        }
                        Text(pluralStringResource(
                            when (frequency) {
                                Recurrence.DAILY -> R.plurals.repeat_unit_days
                                Recurrence.WEEKLY -> R.plurals.repeat_unit_weeks
                                else -> R.plurals.repeat_unit_months
                            }, interval
                        ), modifier = Modifier.testTag("repeat_interval_unit"))
                    }
                    if (!readOnly) Text(
                        stringResource(R.string.repeat_interval_hint),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (showDuePrompt) {
                    Text(stringResource(when {
                        validation == RecurrenceValidationResult.MissingDueDate -> R.string.repeat_needs_date
                        !valid -> R.string.repeat_choose_compatible_date
                        else -> R.string.repeat_due_date
                    }))
                    Text(due?.let(::dateLabel) ?: stringResource(R.string.no_due_date_due_value),
                        modifier = Modifier.testTag("repeat_due"))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = editable, onClick = {
                            defaultToday = true
                            automaticDate = false
                            dateControlsUsed = true
                        }) {
                            Text(stringResource(R.string.today))
                        }
                        TextButton(enabled = editable, onClick = {
                            dateControlsUsed = true
                            showDatePicker(context, due) {
                                dueIso = it.toString()
                                defaultToday = false
                                automaticDate = false
                            }
                        }) { Text(stringResource(R.string.date_picker)) }
                    }
                    ValidationRecurrenceErrorText(validation)
                }
                if (preview != null) {
                    Text(buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
                            append(stringResource(R.string.repeat_preview))
                        }
                        append(": ")
                        append(dateLabel(preview))
                    }, modifier = Modifier.testTag("repeat_preview_date"))
                }
                if (onEditCurrent != null) {
                    TextButton(onClick = onEditCurrent, enabled = !saving) {
                        Text(stringResource(R.string.repeat_edit_current))
                    }
                }
                val error = blockedReason ?: failureReason ?: if (saveFailed) TaskEditFailureReason.STORAGE else null
                if (error != null) {
                    Text(stringResource(if (error == TaskEditFailureReason.STORAGE) R.string.repeat_save_failed
                        else error.messageResId()), color = MaterialTheme.colorScheme.error)
                }
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !saving) {
                        Text(stringResource(if (readOnly || blocked) R.string.close_yes_dialog_button
                            else R.string.cancel_no_dialog_button))
                    }
                    if (!readOnly) TextButton(
                        onClick = {
                            if (valid && editable) {
                                // A failed save must retain the actual attempted date across midnight.
                                dueIso = due?.toString()
                                defaultToday = false
                                onDone(rule, due)
                            }
                        },
                        enabled = valid && editable,
                        modifier = Modifier.testTag("repeat_done")
                    ) { Text(stringResource(R.string.repeat_done)) }
                }
            }
        }
    }
}

/** Taps adjust once; a held press repeats until release, cancellation or the interval limit. */
@Composable
private fun RepeatIntervalButton(
    icon: ImageVector,
    label: String,
    value: Int,
    longClickLabel: String,
    tag: String,
    enabled: Boolean,
    onStep: () -> Unit
) {
    val interactions = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    var repeatJob by remember { mutableStateOf<Job?>(null) }
    val currentStep by rememberUpdatedState(onStep)
    val currentEnabled by rememberUpdatedState(enabled)
    LaunchedEffect(interactions) {
        interactions.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> pressed = true
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    // Stop on the input event, without waiting for another composition frame.
                    pressed = false
                    repeatJob?.cancel()
                    repeatJob = null
                }
            }
        }
    }
    LaunchedEffect(enabled) { if (!enabled) repeatJob?.cancel() }
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = if (enabled) 1f else 0.38f),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = if (enabled) 1f else 0.38f),
        modifier = Modifier.size(48.dp).testTag(tag).semantics {
            contentDescription = label
            stateDescription = value.toString()
        }.combinedClickable(
            enabled = enabled,
            role = Role.Button,
            interactionSource = interactions,
            indication = LocalIndication.current,
            onClick = onStep,
            onLongClickLabel = longClickLabel,
            onLongClick = {
                onStep()
                repeatJob?.cancel()
                if (pressed) repeatJob = scope.launch {
                    while (isActive && pressed && currentEnabled) {
                        delay(100)
                        if (pressed && currentEnabled) currentStep()
                    }
                }
            }
        )
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        }
    }
}

/** IDs are saved; authoritative task state is reloaded when this editor is recreated. */
@Composable
internal fun ExistingRepeatDialog(
    taskId: Int,
    vm: TaskViewModel,
    onEditCurrent: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var current by remember(taskId) { mutableStateOf<Task?>(null) }
    var loaded by remember(taskId) { mutableStateOf(false) }
    // Keep the opening baseline as well as RepeatDialog's draft. Reloading eligibility must
    // never replace an unsuccessful draft with the historical row, including after restoration.
    var openingFrequency by rememberSaveable(taskId) { mutableStateOf<String?>(null) }
    var openingInterval by rememberSaveable(taskId) { mutableIntStateOf(1) }
    var openingDue by rememberSaveable(taskId) { mutableStateOf<String?>(null) }
    var openingBase by rememberSaveable(taskId) { mutableStateOf<String?>(null) }
    var openingTitle by rememberSaveable(taskId) { mutableStateOf<String?>(null) }
    var historical by rememberSaveable(taskId) { mutableStateOf(false) }
    var invalidRule by rememberSaveable(taskId) { mutableStateOf(false) }
    var blockedName by rememberSaveable(taskId) { mutableStateOf<String?>(null) }
    var failedName by rememberSaveable(taskId) { mutableStateOf<String?>(null) }
    var loadFailed by remember(taskId) { mutableStateOf(false) }
    var reload by remember(taskId) { mutableIntStateOf(0) }
    val save by vm.repeatSave.collectAsState()
    LaunchedEffect(taskId, save.taskId == taskId && save.failed, reload) {
        if (save.taskId == taskId && save.failed) {
            failedName = save.failureReason?.name
            if (save.failureReason == TaskEditFailureReason.MISSING_TASK ||
                save.failureReason == TaskEditFailureReason.STALE_REPEAT_TARGET) {
                blockedName = save.failureReason?.name
            }
        }
        // Publish both together: a partially loaded task must not appear historical.
        val (refreshedTask, refreshedCurrent) = try {
            (vm.loadTask(taskId) to vm.editableRepeatTask(taskId)).also { loadFailed = false }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("RepeatDialog", "Could not load repeat target $taskId", e)
            loadFailed = true
            loaded = true
            return@LaunchedEffect
        }
        current = refreshedCurrent
        // Refresh display-only identity without replacing the opening rule/date baseline.
        if (refreshedTask != null) openingTitle = refreshedTask.text
        if (openingFrequency == null && refreshedTask != null) {
            val rule = refreshedTask.repeatRuleOrNull
            openingFrequency = (rule?.recurrence ?: Recurrence.NONE).name
            openingInterval = rule?.interval ?: 1
            openingDue = refreshedTask.due?.toString()
            openingBase = refreshedTask.baseDate?.toString()
            historical = refreshedCurrent?.id != taskId
            invalidRule = rule == null
        }
        if (openingFrequency != null && !historical &&
            (refreshedTask == null || refreshedCurrent?.id != taskId)) {
            blockedName = (if (refreshedTask == null) TaskEditFailureReason.MISSING_TASK
                else TaskEditFailureReason.STALE_REPEAT_TARGET).name
        }
        loaded = true
    }
    LaunchedEffect(save.saved, taskId) {
        if (save.taskId == taskId && save.saved != null) {
            onDismiss()
            vm.resetRepeatSave()
        }
    }
    val frequency = openingFrequency
    if (frequency != null) {
        key(taskId) {
            RepeatDialog(
                initialRule = RepeatRule(Recurrence.valueOf(frequency), openingInterval),
                initialDue = openingDue?.let(LocalDate::parse),
                initialBaseDate = openingBase?.let(LocalDate::parse),
                taskTitle = openingTitle,
                readOnly = historical,
                saving = !loaded || (save.taskId == taskId && save.saving),
                blockedReason = blockedName?.let(TaskEditFailureReason::valueOf),
                failureReason = failedName?.let(TaskEditFailureReason::valueOf),
                invalidStoredRule = invalidRule,
                onDone = { rule, due ->
                    failedName = null
                    vm.saveRepeat(taskId, rule, due)
                },
                onDismiss = onDismiss,
                onEditCurrent = current?.takeIf { it.id != taskId }?.let { target ->
                    { vm.resetRepeatSave(); onEditCurrent(target.id) }
                }
            )
        }
    } else if (loaded) {
        AlertDialog(onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.repeat)) },
            text = { Text(stringResource(if (loadFailed) R.string.repeat_load_failed else R.string.repeat_task_missing)) },
            dismissButton = {
                if (loadFailed) TextButton(onClick = { reload++ }) { Text(stringResource(R.string.task_save_retry)) }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close_yes_dialog_button)) } })
    }
}
