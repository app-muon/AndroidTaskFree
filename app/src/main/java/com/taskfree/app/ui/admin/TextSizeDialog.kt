package com.taskfree.app.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.taskfree.app.R
import com.taskfree.app.ui.components.dialogMaxHeight
import com.taskfree.app.ui.components.dialogResponsiveWidth
import com.taskfree.app.ui.theme.TextScaleController
import com.taskfree.app.ui.theme.TextScaleOption

@Composable
fun TextSizeDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val current by TextScaleController.option.collectAsState()

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(R.color.dialog_background_colour)
            ),
            modifier = Modifier
                .dialogResponsiveWidth()   // 100% phone, ~80% tablet (capped)
                .dialogMaxHeight()         // ~92% of actual window height
        ) {
            // Header styled like ConfirmDialog
            Text(
                text = stringResource(R.string.text_size_title),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = colorResource(R.color.dialog_primary_colour),
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                    )
                    .padding(24.dp),
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall
            )

            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp)) {
                // Options
                TextSizeRow(
                    option = TextScaleOption.SYSTEM_ONLY,
                    selected = current == TextScaleOption.SYSTEM_ONLY
                ) { TextScaleController.setOption(ctx, TextScaleOption.SYSTEM_ONLY) }

                TextSizeRow(TextScaleOption.SMALL, current == TextScaleOption.SMALL) {
                    TextScaleController.setOption(ctx, TextScaleOption.SMALL)
                }
                TextSizeRow(TextScaleOption.NORMAL, current == TextScaleOption.NORMAL) {
                    TextScaleController.setOption(ctx, TextScaleOption.NORMAL)
                }
                TextSizeRow(TextScaleOption.LARGE, current == TextScaleOption.LARGE) {
                    TextScaleController.setOption(ctx, TextScaleOption.LARGE)
                }
                TextSizeRow(TextScaleOption.XL, current == TextScaleOption.XL) {
                    TextScaleController.setOption(ctx, TextScaleOption.XL)
                }
                TextSizeRow(TextScaleOption.XXL, current == TextScaleOption.XXL) {
                    TextScaleController.setOption(ctx, TextScaleOption.XXL)
                }

                // Live preview
                Column(Modifier.padding(top = 12.dp, bottom = 8.dp)) {
                    Text(
                        text = stringResource(R.string.text_size_preview_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = colorResource(R.color.surface_colour)
                    )
                    Text(
                        text = stringResource(R.string.text_size_preview_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorResource(R.color.surface_colour)
                    )
                    Text(
                        text = stringResource(R.string.text_size_preview_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = colorResource(R.color.surface_colour)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            TextScaleController.resetToDefault(ctx)
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = colorResource(R.color.dialog_button_text_colour)
                        )
                    ) { Text(stringResource(R.string.reset_to_default)) }

                    Spacer(Modifier.width(8.dp))

                    TextButton(
                        onClick = onClose,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = colorResource(R.color.dialog_button_text_colour)
                        )
                    ) { Text(stringResource(R.string.close)) }
                }
            }
        }
    }
}

@Composable
private fun TextSizeRow(
    option: TextScaleOption,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val label = when (option) {
        TextScaleOption.SYSTEM_ONLY -> stringResource(R.string.text_size_system_only)
        TextScaleOption.SMALL -> stringResource(R.string.text_size_small, "0.90×")
        TextScaleOption.NORMAL -> stringResource(R.string.text_size_normal, "1.00×")
        TextScaleOption.LARGE -> stringResource(R.string.text_size_large, "1.15×")
        TextScaleOption.XL -> stringResource(R.string.text_size_xl, "1.30×")
        TextScaleOption.XXL -> stringResource(R.string.text_size_xxl, "1.60×")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)                  // good touch target
            .selectable(                             // whole row is tappable (a11y)
                selected = selected,
                onClick = onSelect,
                role = Role.RadioButton
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically // <- aligns radio with text
    ) {
        RadioButton(
            selected = selected,
            onClick = null // handled by Row.selectable to avoid double events
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = colorResource(R.color.surface_colour),
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}
