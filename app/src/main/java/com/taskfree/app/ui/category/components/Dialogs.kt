// Dialogs.kt
package com.taskfree.app.ui.category.components

import androidx.compose.runtime.saveable.listSaver
import com.taskfree.app.data.entities.Category


sealed interface Dialogs {
    data object None : Dialogs
    data object Add  : Dialogs
    data class Options(val category: Category) : Dialogs
    data class ConfirmDelete(val category: Category) : Dialogs
    data class ConfirmArchive(val category: Category) : Dialogs
}

/** Preserve the open editor while notification routing waits for it to close. */
internal val CategoryDialogsSaver = listSaver<Dialogs, Any>(
    save = { dialog ->
        val category = when (dialog) {
            is Dialogs.Options -> dialog.category
            is Dialogs.ConfirmDelete -> dialog.category
            is Dialogs.ConfirmArchive -> dialog.category
            else -> null
        }
        val kind = when (dialog) {
            Dialogs.None -> "none"
            Dialogs.Add -> "add"
            is Dialogs.Options -> "options"
            is Dialogs.ConfirmDelete -> "delete"
            is Dialogs.ConfirmArchive -> "archive"
        }
        listOf(kind, category?.id ?: 0, category?.title ?: "", category?.color ?: 0L,
            category?.categoryPageOrder ?: 0)
    },
    restore = {
        val category = Category(it[1] as Int, it[2] as String, it[3] as Long, it[4] as Int)
        when (it[0] as String) {
            "add" -> Dialogs.Add
            "options" -> Dialogs.Options(category)
            "delete" -> Dialogs.ConfirmDelete(category)
            "archive" -> Dialogs.ConfirmArchive(category)
            else -> Dialogs.None
        }
    }
)
