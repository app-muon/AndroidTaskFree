// ui/components/EditableRow.kt
package com.taskfree.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.taskfree.app.R
import com.taskfree.app.ui.theme.PanelColors

private object EditableRowConstants {
    val HORIZONTAL_PADDING = 16.dp
    val VERTICAL_PADDING = 4.dp
    val SPACER_WIDTH = 4.dp
}

@Composable
fun EditableMetaRow(
    label: String,
    value: @Composable () -> Unit,
    currentField: Boolean,
    onEdit: () -> Unit,
    colors: PanelColors,
    modifier: Modifier = Modifier
) = MetaRow(colors = colors, modifier = modifier, headlineContent = {
    TaskFieldHeading(label)
}, supportingContent = value, trailingContent = {
    if (!currentField) {
        TrailingEditIcon(onEdit)
    }
})

@Composable
fun MetaRow(
    colors: PanelColors,
    headlineContent: @Composable () -> Unit,
    supportingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    CompositionLocalProvider(LocalContentColor provides colors.surfaceText) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(colors.dialogBackground)
                .padding(
                    horizontal = EditableRowConstants.HORIZONTAL_PADDING,
                    vertical = EditableRowConstants.VERTICAL_PADDING
                )
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                itemVerticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(EditableRowConstants.SPACER_WIDTH)
            ) {
                headlineContent()
                supportingContent?.invoke()
                trailingContent?.invoke()
            }
        }
    }
}

@Composable
fun EditCancelRow(
    onCancel: () -> Unit,
    colors: PanelColors,
    modifier: Modifier = Modifier,
    onSave: (() -> Unit)? = null,
    saveEnabled: Boolean = true
) {
    Row(
        modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End
    ) {
        TextButton(onClick = onCancel) {
            Text(
                modifier = Modifier.padding(end = EditableRowConstants.HORIZONTAL_PADDING),
                text = stringResource(R.string.cancel_no_dialog_button),
                color = colors.dialogButtonText
            )
        }

        // Only show save button if onSave is provided
        onSave?.let { saveAction ->
            TextButton(
                onClick = saveAction,
                enabled = saveEnabled,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = colors.dialogButtonText,
                    disabledContentColor = colors.dialogButtonText.copy(alpha = 0.5f)
                )
            ) {
                Text(
                    text = stringResource(R.string.save_yes_dialog_button)
                )
            }
        }
    }
}