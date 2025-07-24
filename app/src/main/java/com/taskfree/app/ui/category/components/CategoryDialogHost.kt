package com.taskfree.app.ui.category.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.taskfree.app.R
import com.taskfree.app.data.entities.Category
import com.taskfree.app.ui.components.ConfirmArchive
import com.taskfree.app.ui.components.ConfirmDeletion

/**
 * Shows whichever dialog is currently requested by [dialogs].
 *
 * @param dialogs     current dialog state
 * @param setDialogs  mutate dialog state (e.g. { dialogs = it })
 * @param onAdd       create a category
 * @param onNavigateToCategory    navigate to view all the tasks in that category.
 * @param onDelete    delete a category (+ its tasks)
 */
@Composable
internal fun CategoryDialogHost(
    dialogs: Dialogs,
    setDialogs: (Dialogs) -> Unit,
    onAdd: (String) -> Unit,
    onNavigateToCategory: (Int) -> Unit,
    onDelete: (Category) -> Unit,
    onArchive: (Category) -> Unit
) {
    when (dialogs) {
        is Dialogs.None -> Unit

        Dialogs.Add -> CategoryAddDialog(initialText = "",
            confirmButtonText = stringResource(R.string.add_category_yes_dialog_button),
            onConfirm = { onAdd(it); setDialogs(Dialogs.None) },
            onDismiss = { setDialogs(Dialogs.None) })

        is Dialogs.Options -> {
            var hasInteracted = false

            CategoryOptionsPanel(category = dialogs.category, onRequestDelete = {
                hasInteracted = true
                setDialogs(Dialogs.ConfirmDelete(dialogs.category))
            },
                onRequestArchive = {
                    hasInteracted = true
                    setDialogs(Dialogs.ConfirmArchive(dialogs.category))
                },
                onDismiss = {
                if (!hasInteracted) setDialogs(Dialogs.None)
            },
                onNavigateToCategory = onNavigateToCategory)
        }

        is Dialogs.ConfirmDelete -> ConfirmDeletion(title = stringResource(R.string.delete_this_category_action),
            message = stringResource(
                R.string.are_you_sure_you_delete_category, dialogs.category.title
            ),
            onYes = { onDelete(dialogs.category); setDialogs(Dialogs.None) },
            onNo = { setDialogs(Dialogs.None) })

        is Dialogs.ConfirmArchive -> ConfirmArchive(
            title = stringResource(R.string.confirm_archived_completed_title),
            message = stringResource(R.string.confirm_archive_category_completed_msg),
            onYes = { onArchive(dialogs.category); setDialogs(Dialogs.None) },
            onNo = { setDialogs(Dialogs.None) }
        )
    }
}
