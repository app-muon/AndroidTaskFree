package com.taskfree.app.ui.task

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.taskfree.app.R
import com.taskfree.app.ui.components.AutoLinkedText
import com.taskfree.app.ui.components.ConfirmDialog
import com.taskfree.app.ui.components.formatDateTime
import com.taskfree.app.ui.theme.outlinedFieldColours
import com.taskfree.app.ui.theme.providePanelColors
import java.time.ZoneId

@Composable
fun TaskNotesSection(state: TaskSheetState, vm: TaskViewModel, onTextFocus: () -> Unit) {
    var showAll by rememberSaveable(state.taskId) { mutableStateOf(false) }
    var deleting by rememberSaveable(state.taskId) { mutableStateOf<Int?>(null) }
    val locale = LocalConfiguration.current.locales[0]
    val zone = ZoneId.systemDefault()
    val colors = providePanelColors()
    // The sheet's own button colour, as in EditCancelRow, rather than Material's default.
    val buttonColors = ButtonDefaults.textButtonColors(
        contentColor = colors.dialogButtonText,
        disabledContentColor = colors.dialogButtonText.copy(alpha = 0.5f)
    )
    // Centres a note's first line of text on its date and pencil, whatever the text scale.
    val lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
    val firstLineOffset = with(LocalDensity.current) {
        if (lineHeight.isSp) ((noteActionHeight - lineHeight.toDp()) / 2).coerceAtLeast(0.dp) else 0.dp
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (state.hasError) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.task_save_failed), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.retryTextSave() }, enabled = !state.closing, colors = buttonColors) {
                    Text(stringResource(R.string.task_save_retry))
                }
            }
        }
        // The composer opens on request and stays while it is focused or holds a draft.
        if (state.active == TaskTextField.Composer || state.composer.text.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Stays enabled while adding, keeping focus and the keyboard; the editor ignores input then.
                TaskTextEditor(
                    value = state.composer,
                    field = TaskTextField.Composer,
                    active = state.active == TaskTextField.Composer,
                    limitReached = state.composerLimitReached,
                    vm = vm,
                    onTextFocus = onTextFocus,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { vm.addNote() },
                    enabled = state.composer.text.isNotBlank() && !state.submitting, colors = buttonColors) {
                    Text(stringResource(R.string.add_category_yes_dialog_button))
                }
            }
        } else {
            TextButton(onClick = { onTextFocus(); vm.focusText(TaskTextField.Composer) },
                modifier = Modifier.height(noteActionHeight).testTag("add-note"),
                colors = buttonColors, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(stringResource(R.string.add_note))
            }
        }
        val visible = if (showAll) state.notes else state.notes.take(5)
        visible.forEach { note ->
            key(note.id) {
                var expanded by rememberSaveable { mutableStateOf(false) }
                var overflows by remember(note.text) { mutableStateOf(false) }
                val field = TaskTextField.Note(note.id)
                val editing = state.active == field
                val draft = state.edits[note.id]
                Column(Modifier.fillMaxWidth().padding(top = 2.dp).testTag("note-${note.id}")) {
                    if (editing && draft != null) {
                        TaskTextEditor(draft.value, field, true, draft.limitReached, vm = vm,
                            onTextFocus = onTextFocus)
                        TextButton(onClick = { deleting = note.id }, colors = buttonColors) {
                            Text(stringResource(R.string.delete_yes_dialog_button))
                        }
                    } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f).padding(top = firstLineOffset)) {
                            AutoLinkedText(
                                raw = draft?.value?.text?.trim()?.takeIf { it.isNotBlank() } ?: note.text,
                                color = colors.surfaceText,
                                maxLines = if (expanded) Int.MAX_VALUE else 3,
                                onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow }
                            )
                            if (expanded || overflows) TextButton(onClick = { expanded = !expanded },
                                modifier = Modifier.height(noteActionHeight),
                                colors = buttonColors, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Text(stringResource(if (expanded) R.string.note_collapse else R.string.note_expand))
                            }
                        }
                        Row(Modifier.height(noteActionHeight), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(formatDateTime(note.createdAt, locale, zone),
                                style = MaterialTheme.typography.labelSmall, color = colors.surfaceText)
                            if (note.editedAt != null) Text(stringResource(R.string.note_edited),
                                style = MaterialTheme.typography.labelSmall, color = colors.surfaceText)
                            IconButton(onClick = { onTextFocus(); vm.focusText(field) },
                                modifier = Modifier.size(noteActionHeight)) {
                                Icon(Icons.Default.Edit, stringResource(R.string.edit_note),
                                    tint = colors.surfaceText, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
        if (state.notes.size > 5) TextButton(onClick = { showAll = !showAll }, colors = buttonColors) {
            Text(if (showAll) stringResource(R.string.notes_show_fewer)
                else stringResource(R.string.notes_show_all, state.notes.size))
        }
    }

    deleting?.let { id ->
        ConfirmDialog(
            title = stringResource(R.string.delete_note_title),
            message = stringResource(R.string.delete_note_message),
            yesMessage = stringResource(R.string.delete_yes_dialog_button),
            onYes = { vm.deleteNote(id); deleting = null },
            onNo = { deleting = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class) // OutlinedTextFieldDefaults.DecorationBox and Container
@Composable
internal fun TaskTextEditor(
    value: TextFieldValue,
    field: TaskTextField,
    active: Boolean,
    limitReached: Boolean,
    vm: TaskViewModel,
    onTextFocus: () -> Unit,
    modifier: Modifier = Modifier
) {
    val title = field == TaskTextField.Title
    val focus = LocalFocusManager.current
    val activity = LocalContext.current.activity()
    val requester = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    LaunchedEffect(active) { if (active) requester.requestFocus() }
    val colors = providePanelColors().outlinedFieldColours()
    val interactions = remember { MutableInteractionSource() }
    val placeholder: (@Composable () -> Unit)? = if (field == TaskTextField.Composer) {
        { Text(stringResource(R.string.write_note)) }
    } else null
    val supportingText: (@Composable () -> Unit)? = if (limitReached) {
        { Text(stringResource(if (title) R.string.title_limit_reached else R.string.note_limit_reached)) }
    } else null
    // OutlinedTextField's decoration with less vertical padding than its fixed 56dp minimum.
    BasicTextField(
        value = value,
        onValueChange = { vm.editText(field, it) },
        modifier = modifier.fillMaxWidth().heightIn(max = 160.dp)
            .testTag(when (field) {
                TaskTextField.Title -> "task-title-editor"
                TaskTextField.Composer -> "note-composer"
                is TaskTextField.Note -> "note-editor-${field.id}"
            })
            .focusRequester(requester)
            .onFocusChanged {
                if (it.isFocused) {
                    hadFocus = true
                    onTextFocus()
                    vm.focusText(field)
                } else if (hadFocus && activity?.isChangingConfigurations != true) {
                    hadFocus = false
                    vm.blurText(field)
                }
            },
        textStyle = LocalTextStyle.current.copy(color = colors.focusedTextColor),
        cursorBrush = SolidColor(if (limitReached) colors.errorCursorColor else colors.cursorColor),
        singleLine = title,
        // The composer starts one line high and grows with its text.
        minLines = if (field is TaskTextField.Note) 2 else 1,
        maxLines = if (title) 1 else 5,
        keyboardOptions = KeyboardOptions(imeAction = if (title) ImeAction.Done else ImeAction.Default),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); vm.blurText(field) }),
        interactionSource = interactions,
        decorationBox = { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value.text,
                innerTextField = innerTextField,
                enabled = true,
                singleLine = title,
                visualTransformation = VisualTransformation.None,
                interactionSource = interactions,
                isError = limitReached,
                placeholder = placeholder,
                supportingText = supportingText,
                colors = colors,
                contentPadding = OutlinedTextFieldDefaults.contentPadding(top = 8.dp, bottom = 8.dp),
                container = {
                    OutlinedTextFieldDefaults.Container(enabled = true, isError = limitReached,
                        interactionSource = interactions, colors = colors)
                }
            )
        }
    )
}

// Compact controls keep the notes list dense; Compose still expands their touch targets to 48dp.
private val noteActionHeight = 24.dp

internal tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
