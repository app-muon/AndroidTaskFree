// CategoryOptionsPanel.kt
package com.taskfree.app.ui.category.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskfree.app.R
import com.taskfree.app.data.entities.Category
import com.taskfree.app.ui.category.CategoryViewModel
import com.taskfree.app.ui.category.CategoryVmFactory
import com.taskfree.app.ui.components.ActionItem
import com.taskfree.app.ui.components.CategoryPill
import com.taskfree.app.ui.components.EditCancelRow
import com.taskfree.app.ui.components.EditableMetaRow
import com.taskfree.app.ui.components.PanelActionList
import com.taskfree.app.ui.theme.categoryPalette
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
    // Track & show current colour
    var currentColor by remember { mutableLongStateOf(category.color) }
    val previewCategory = remember(category.id, currentName, currentColor) {
        category.copy(title = currentName, color = currentColor)
    }
    var isColorEditing by remember { mutableStateOf(false) }

    val editButtonSizeConst = 32.dp
    val editIconSizeConst = 20.dp
    val swatchSizeConst = 20.dp

    PanelActionList(
        // replace your current headerContent block with this
        headerContent = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                // --- Name + Color on a single line ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    EditableMetaRow(
                        label = "",
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
                                    EditCancelRow(
                                        onCancel = { editingField = CategoryEditingField.NONE },
                                        onSave = {
                                            val trimmed = newName.trim()
                                            catVm.rename(category, trimmed)
                                            currentName = trimmed
                                            editingField = CategoryEditingField.NONE
                                        },
                                        saveEnabled = newName.isNotBlank(),
                                        colors = colors
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
                        colors = colors,
                        modifier = Modifier.weight(1f)
                    )

                    // swatch + pencil on the right
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 16.dp) // match left margin
                    ) {
                        Canvas(
                            modifier = Modifier
                                .size(swatchSizeConst)
                                .clickable { isColorEditing = !isColorEditing }
                        ) {
                            drawCircle(
                                color = androidx.compose.ui.graphics.Color((currentColor and 0xFFFFFFFFL).toInt())
                            )
                        }

                        androidx.compose.material3.IconButton(
                            onClick = { isColorEditing = !isColorEditing },
                            modifier = Modifier.size(editButtonSizeConst) // 48.dp touch target
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = stringResource(R.string.category_colour_label),
                                tint = colors.surfaceText,
                                modifier = Modifier.size(editIconSizeConst) // 24.dp glyph
                            )
                        }
                    }
                }

                // --- Expanded palette appears directly under the title row ---
                if (isColorEditing) {
                    Spacer(Modifier.padding(top = 8.dp))
                    ColorChooserGrid(
                        colors = colors, currentColor = currentColor, onPick = { picked ->
                            currentColor = picked
                            catVm.updateColor(category, picked)
                            isColorEditing = false
                        })
                }
            }
        }, onDismiss = onDismiss, actions = listOf(
            ActionItem(icon = Icons.AutoMirrored.Filled.List, onClick = {
                onDismiss()
                onNavigateToCategory(category.id)
            }, labelContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.go_to_category_action),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.surfaceText,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    CategoryPill(
                        category = previewCategory, big = true, selected = true
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

@Composable
private fun ColorChooserGrid(
    colors: com.taskfree.app.ui.theme.PanelColors,
    currentColor: Long,
    onPick: (Long) -> Unit
) {
    val swatchDiameter = 28.dp
    val touchTarget = 40.dp // larger for accessibility
    val strokeWidth = 3.dp

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = (touchTarget + 10.dp)),
        userScrollEnabled = false,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .heightIn(max = 200.dp)
    ) {
        items(categoryPalette) { swatch ->
            val swatchLong = (swatch.toArgb().toLong() and 0xFFFFFFFFL)
            val selected = swatchLong == (currentColor and 0xFFFFFFFFL)

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(touchTarget)
                    .clickable { onPick(swatchLong) }
            ) {
                Canvas(modifier = Modifier.size(swatchDiameter)) {
                    drawCircle(color = swatch)
                    if (selected) {
                        drawCircle(
                            color = colors.surfaceText.copy(alpha = 0.9f),
                            style = Stroke(width = strokeWidth.toPx())
                        )
                    }
                }
            }
        }
    }
}
