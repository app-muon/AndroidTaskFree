// CategoryOptionsPanel.kt
package com.taskfree.app.ui.category.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskfree.app.R
import com.taskfree.app.data.entities.Category
import com.taskfree.app.ui.category.CategoryViewModel
import com.taskfree.app.ui.category.CategoryVmFactory
import com.taskfree.app.ui.components.ActionItem
import com.taskfree.app.ui.components.EditCancelRow
import com.taskfree.app.ui.components.EditableMetaRow
import com.taskfree.app.ui.components.PanelActionList
import com.taskfree.app.ui.theme.outlinedFieldColours
import com.taskfree.app.ui.theme.providePanelColors

// Simple enum for category editing states
private enum class CategoryEditingField { NONE, NAME }

/**
 * Bottom-sheet-style panel offering *Rename* / *Delete* actions.
 */
@Composable
internal fun CategoryOptionsPanel(
    category: Category,
    onRequestDelete: () -> Unit,
    onNavigateToCategory: (Int) -> Unit,
    onRequestArchive: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current.applicationContext as android.app.Application
    val catVm: CategoryViewModel = viewModel(factory = CategoryVmFactory(context))
    val colors = providePanelColors()

    // Local state management
    var editingField by remember { mutableStateOf(CategoryEditingField.NONE) }
    var currentName by remember { mutableStateOf(category.title) }
    PanelActionList(headerContent = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            /* --- CATEGORY NAME ROW --- */
            EditableMetaRow(label = "", // No label for category name
                value = {
                    if (editingField == CategoryEditingField.NAME) {
                        var newName by rememberSaveable { mutableStateOf(currentName) }

                        Column {
                            OutlinedTextField(
                                value = newName,
                                onValueChange = { newName = it },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = colors.outlinedFieldColours()
                            )
                            EditCancelRow(onCancel = {
                                editingField = CategoryEditingField.NONE
                            }, onSave = {
                                val trimmed = newName.trim()
                                catVm.rename(category, trimmed)
                                currentName = trimmed
                                editingField = CategoryEditingField.NONE
                            }, saveEnabled = newName.isNotBlank(), colors = colors
                            )
                        }
                    } else {
                        Text(
                            text = currentName,
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.surfaceText
                        )
                    }
                },
                currentField = editingField == CategoryEditingField.NAME,
                onEdit = { editingField = CategoryEditingField.NAME },
                colors = colors
            )
        }
    },
        onDismiss = onDismiss,
        actions = listOf(ActionItem(icon = Icons.AutoMirrored.Filled.List, onClick = {
            onDismiss()
            onNavigateToCategory(category.id)
        }, labelContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.go_to_category_action),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.surfaceText
                )
            }
        }),
            // ——— Archive completed ———
            ActionItem(icon = Icons.Default.Archive, onClick = {
                onRequestArchive()
            }, labelContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.archive_completed_in_category_action),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.surfaceText
                    )
                }
            }),

            ActionItem(
                labelContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.delete_this_category_action),
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.surfaceText
                        )
                    }
                },
                icon = Icons.Default.Delete,
                iconTint = colors.brightRed,
                onClick = onRequestDelete
            )
        )
    )
}