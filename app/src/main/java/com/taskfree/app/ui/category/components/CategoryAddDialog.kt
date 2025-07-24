// CategoryAddDialog.kt
package com.taskfree.app.ui.category.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.taskfree.app.R
import com.taskfree.app.ui.theme.outlinedFieldColours
import com.taskfree.app.ui.theme.providePanelColors

/**
 * Reusable text-entry dialog for *Add* and *Rename*.
 */
@Composable
internal fun CategoryAddDialog(
    initialText: String,
    confirmButtonText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialText) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val backgroundColor = colorResource(R.color.dialog_background_colour)

    // Autofocus only when creating a new category
    LaunchedEffect(Unit) {
        if (initialText.isBlank()) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    Dialog(
        onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = backgroundColor,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large
        ) {
            Column {
                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(R.string.add_category_button_label),
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ListItemDefaults.colors(
                        containerColor = colorResource(R.color.dialog_background_colour),
                        headlineColor = colorResource(R.color.surface_colour)
                    ),
                )
                HorizontalDivider(color = Color.Gray)
                Spacer(Modifier.height(18.dp))

                Column {
                    OutlinedTextField(
                        colors = providePanelColors().outlinedFieldColours(),
                        label = { Text(stringResource(R.string.category_name)) },
                        modifier = Modifier
                            .focusRequester(focusRequester)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        onValueChange = { text = it },
                        singleLine = true,
                        value = text
                    )

                    Row(
                        Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(
                                stringResource(R.string.cancel_no_dialog_button),
                                color = colorResource(R.color.dialog_button_text_colour)
                            )
                        }
                        TextButton(
                            onClick = { if (text.isNotBlank()) onConfirm(text.trim()) },
                            enabled = text.isNotBlank()
                        ) {
                            Text(
                                confirmButtonText,
                                color = if (text.isNotBlank()) colorResource(R.color.dialog_button_text_colour) else Color.Gray
                            )
                        }
                    }
                }
            }
        }
    }
}
