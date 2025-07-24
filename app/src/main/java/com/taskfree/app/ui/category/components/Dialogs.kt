// Dialogs.kt
package com.taskfree.app.ui.category.components

import com.taskfree.app.data.entities.Category


sealed interface Dialogs {
    data object None : Dialogs
    data object Add  : Dialogs
    data class Options(val category: Category) : Dialogs
    data class ConfirmDelete(val category: Category) : Dialogs
    data class ConfirmArchive(val category: Category) : Dialogs
}