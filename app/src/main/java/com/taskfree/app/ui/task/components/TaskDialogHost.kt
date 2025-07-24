package com.taskfree.app.ui.task.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.taskfree.app.R
import com.taskfree.app.data.entities.Category
import com.taskfree.app.domain.model.Recurrence
import com.taskfree.app.ui.components.ConfirmArchive
import com.taskfree.app.ui.components.DueChoice
import com.taskfree.app.ui.components.NotificationOption
import com.taskfree.app.ui.components.fromTask
import com.taskfree.app.ui.task.TaskOptionsPanel
import com.taskfree.app.ui.task.TaskViewModel

@Composable
internal fun TaskDialogHost(
    dialogs: TaskDialogs,
    setDialogs: (TaskDialogs) -> Unit,
    allCategories: List<Category>,
    taskVm: TaskViewModel,
    currentFilterCatId: Int?,
    onNavigateToCategory: (Int) -> Unit,
) {
    when (dialogs) {
        is TaskDialogs.None -> Unit

        is TaskDialogs.Add -> {
            NewTaskDialog(
                initialText = "",
                initialDue = dialogs.dueChoice,
                initialRecurrence = Recurrence.NONE,
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
            var hasInteracted = false      // <-- NEW

            TaskOptionsPanel(
                task = ti.task, taskVm = taskVm, onArchive = { _, mode ->
                    hasInteracted = true
                    setDialogs(TaskDialogs.ConfirmArchive(ti, mode))
                },

                onNavigateToCategory = { catId ->
                    hasInteracted = true
                    setDialogs(TaskDialogs.None)
                    onNavigateToCategory(catId)
                },

                onPostpone = { task ->
                    taskVm.updateDue(task, DueChoice.from(java.time.LocalDate.now().plusDays(1)))
                    setDialogs(TaskDialogs.None)
                },

                onClone = { original ->
                    taskVm.add(
                        original.text + " (copy)",
                        DueChoice.fromTask(original),
                        original.recurrence,
                        original.categoryId,
                        NotificationOption.fromTask(original)
                    )
                    setDialogs(TaskDialogs.None)
                },

                onDismiss = {
                    if (!hasInteracted) setDialogs(TaskDialogs.None)   // <-- only clear if untouched
                },

                currentFilterCatId = currentFilterCatId
            )
        }

        is TaskDialogs.ConfirmArchive -> {
            ConfirmArchive(title = "Confirm archive", message = when (dialogs.mode) {
                ArchiveMode.Single -> stringResource(R.string.are_you_sure_you_want_to_archive_task)
                ArchiveMode.Series -> stringResource(R.string.are_you_sure_you_want_to_archive_series)
            }, onYes = {
                taskVm.archive(dialogs.task.task, dialogs.mode)
                setDialogs(TaskDialogs.None)
            }, onNo = { setDialogs(TaskDialogs.None) })
        }
    }
}
