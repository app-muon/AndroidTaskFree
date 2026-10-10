// ConfirmDialog.kt
package com.taskfree.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.taskfree.app.R

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    yesMessage: String,
    noMessage: String = stringResource(R.string.cancel_no_dialog_button),
    noColour: Color = colorResource(R.color.dialog_button_text_colour),
    yesColour: Color = colorResource(R.color.dark_red),
    onYes: () -> Unit,
    onNo: () -> Unit,
    onDismiss: () -> Unit = onNo,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    Dialog(
        onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            Modifier
                .dialogResponsiveWidth()
                .dialogMaxHeight(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = colorResource(R.color.dialog_background_colour))
        ) {
            Column {
                // Header with full width background
                Text(
                    text = title,
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

                // Message and optional content scroll between the fixed header and buttons
                val scrollState = rememberScrollState()
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .thinVerticalScrollbar(
                            scrollState, thickness = 3.dp, endInset = 8.dp, color = Color.Gray
                        )
                        .verticalScroll(scrollState)
                        .padding(start = 24.dp, end = 24.dp, top = 20.dp)
                ) {
                    AutoLinkedText(
                        raw = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorResource(R.color.surface_colour)
                    )
                    content?.invoke(this)
                }

                // Buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (noMessage.isNotEmpty()) {
                        TextButton(
                            onClick = onNo,
                            colors = ButtonDefaults.textButtonColors(contentColor = noColour)
                        ) {
                            Text(noMessage)
                        }

                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    TextButton(
                        onClick = onYes,
                        colors = ButtonDefaults.textButtonColors(contentColor = yesColour)
                    ) {
                        Text(yesMessage)
                    }
                }
            }
        }
    }
}

@Composable
fun ConfirmDeletion(
    title: String, message: String, onYes: () -> Unit, onNo: () -> Unit
) {
    ConfirmDialog(
        title = title,
        message = message,
        yesMessage = stringResource(R.string.delete_yes_dialog_button),
        onYes = onYes,
        onNo = onNo,
        yesColour = colorResource(R.color.bright_red)
    )
}


@Composable
fun ConfirmArchive(
    title: String,
    message: String,
    onYes: () -> Unit,
    onNo: () -> Unit,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    ConfirmDialog(
        title = title,
        message = message,
        yesMessage = stringResource(R.string.archive_task_yes_dialog_button),
        onYes = onYes,
        onNo = onNo,
        content = content
    )
}
