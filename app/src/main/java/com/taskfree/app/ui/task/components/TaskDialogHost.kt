package com.taskfree.app.ui.task.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.taskfree.app.R
import com.taskfree.app.data.entities.Category
import com.taskfree.app.ui.components.ConfirmArchive
import com.taskfree.app.ui.components.ConfirmDeletion
import com.taskfree.app.ui.components.ConfirmDialog
import com.taskfree.app.ui.components.DueChoice
import com.taskfree.app.ui.components.DueChoiceSaver
import com.taskfree.app.ui.task.TaskOptionsPanel
import com.taskfree.app.ui.task.NoteSearchTarget
import com.taskfree.app.ui.task.TaskViewModel

@Composable
internal fun TaskDialogHost(
    dialogs: TaskDialogs,
    setDialogs: (TaskDialogs) -> Unit,
    allCategories: List<Category>,
    taskVm: TaskViewModel,
    currentFilterCatId: Int?,
    onNavigateToCategory: (Int) -> Unit,
    onRestored: () -> Unit = {},
    onModalState: (Boolean) -> Unit = {},
) {
    // Save only navigation identity; task contents are always reloaded from Room.
    var savedKind by rememberSaveable { mutableStateOf("") }
    var savedTaskId by rememberSaveable { mutableIntStateOf(0) }
    var savedNoteTarget by rememberSaveable(stateSaver = NoteSearchTarget.Saver) {
        mutableStateOf<NoteSearchTarget?>(null)
    }
    var savedCategoryId by rememberSaveable { mutableIntStateOf(0) }
    var savedDue by rememberSaveable(stateSaver = DueChoiceSaver) { mutableStateOf<DueChoice>(DueChoice.None) }
    var restored by remember { mutableStateOf(false) }
    // Retain Repeat independently so a deleted parent cannot discard its unsuccessful draft.
    var repeatTaskId by rememberSaveable { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        if (dialogs == TaskDialogs.None) {
            taskVm.restoreDialog(savedKind, savedTaskId, savedCategoryId, savedDue,
                savedNoteTarget)
        }
        restored = true
    }
    SideEffect {
        onModalState(dialogs != TaskDialogs.None || repeatTaskId != null)
        if (restored) {
            onRestored()
            when (dialogs) {
                is TaskDialogs.Options -> {
                    savedKind = "options"
                    savedTaskId = dialogs.task.task.id
                    savedNoteTarget = dialogs.noteTarget
                }
                is TaskDialogs.Add -> {
                    savedKind = "add"
                    savedCategoryId = dialogs.category.id
                    savedDue = dialogs.dueChoice
                }
                is TaskDialogs.ConfirmArchive -> {
                    savedKind = if (dialogs.mode == ArchiveMode.Series) "archive_series" else "archive_single"
                    savedTaskId = dialogs.task.task.id
                }
                is TaskDialogs.ConfirmClone -> {
                    savedKind = "clone"
                    savedTaskId = dialogs.task.task.id
                }
                is TaskDialogs.ConfirmPermanentDelete -> {
                    savedKind = "delete"
                    savedTaskId = dialogs.task.task.id
                }
                else -> savedKind = ""
            }
        }
    }
    when (dialogs) {
        is TaskDialogs.None -> Unit

        is TaskDialogs.Add -> {
            NewTaskDialog(
                initialText = "",
                initialDue = dialogs.dueChoice,
                initialCategory = dialogs.category,
                allCategories = allCategories,
                onSave = { text, due, rec, catId, notify ->
                    taskVm.add(
                        text = text,
                        due = due,
                        rec = rec,
                        categoryId = catId,
                        notify = notify
                    )
                    setDialogs(TaskDialogs.None)
                },
                onDismiss = { setDialogs(TaskDialogs.None) }
            )
        }


        is TaskDialogs.Options -> {
            val ti = dialogs.task          // local snapshot

            TaskOptionsPanel(
                task = ti, taskVm = taskVm, onArchive = { taskToArchive, mode ->
                    setDialogs(TaskDialogs.ConfirmArchive(ti.copy(task = taskToArchive), mode))
                },

                onNavigateToCategory = { catId ->
                    // Navigation can dispose this composition before its next SideEffect.
                    savedKind = ""
                    savedTaskId = 0
                    setDialogs(TaskDialogs.None)
                    onNavigateToCategory(catId)
                },

                onClone = { taskToClone ->
                    setDialogs(TaskDialogs.ConfirmClone(ti.copy(task = taskToClone)))   // <-- show the Y/N dialog
                },

                onDeletePermanently = { taskToDelete ->
                    setDialogs(TaskDialogs.ConfirmPermanentDelete(ti.copy(task = taskToDelete)))
                },

                onDismiss = {
                    setDialogs(TaskDialogs.None)
                },

                currentFilterCatId = currentFilterCatId,
                noteTarget = dialogs.noteTarget,
                onOpenRepeat = { repeatTaskId = it }
            )
        }

        is TaskDialogs.ConfirmArchive -> {
            ConfirmArchive(
                title = stringResource(if (dialogs.mode == ArchiveMode.Series)
                    R.string.menu_archive_series_action_confirmation else R.string.menu_archive_task_action_confirmation),
                message = when (dialogs.mode) {
                ArchiveMode.Single -> stringResource(R.string.are_you_sure_you_want_to_archive_task)
                ArchiveMode.Series -> stringResource(R.string.are_you_sure_you_want_to_archive_series)
            }, onYes = {
                taskVm.archive(dialogs.task.task, dialogs.mode)
                setDialogs(TaskDialogs.None)
            }, onNo = { setDialogs(TaskDialogs.None) })
        }
        is TaskDialogs.ConfirmClone -> {
            val titleTemplate = stringResource(R.string.task_name_when_copy)
            ConfirmDialog(
                title = stringResource(R.string.clone_task_action),
                message = stringResource(R.string.clone_task_confirm_message),
                yesMessage = stringResource(R.string.clone_yes_dialog_button),
                onYes = {
                    taskVm.clone(dialogs.task.task.id, titleTemplate)
                    setDialogs(TaskDialogs.None)
                },
                onNo = { setDialogs(TaskDialogs.None) }
            )
        }
        is TaskDialogs.ConfirmPermanentDelete -> {
            ConfirmDeletion(
                title = stringResource(R.string.confirm_permanently_delete_task_title),
                message = stringResource(R.string.confirm_permanently_delete_task_msg),
                onYes = {
                    taskVm.deletePermanently(dialogs.task.task)
                    setDialogs(TaskDialogs.None)
                },
                onNo = { setDialogs(TaskDialogs.None) }
            )
        }

    }
    repeatTaskId?.let { id ->
        key(id) {
            ExistingRepeatDialog(
                taskId = id,
                vm = taskVm,
                onEditCurrent = { repeatTaskId = it },
                onDismiss = { repeatTaskId = null; taskVm.resetRepeatSave() }
            )
        }
    }
}
