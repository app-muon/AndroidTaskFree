// ConfirmDialog.kt
package com.taskfree.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
    onNo: () -> Unit
) {
    Dialog(onDismissRequest = onNo) {
        Card(
            modifier = Modifier.fillMaxWidth(),
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

                // Message
                Text(
                    text = message,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorResource(R.color.surface_colour)
                )

                // Buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onNo,
                        colors = ButtonDefaults.textButtonColors(contentColor = noColour)
                    ) {
                        Text(noMessage)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

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
    title: String, message: String, onYes: () -> Unit, onNo: () -> Unit
) {
    ConfirmDialog(
        title = title,
        message = message,
        yesMessage = stringResource(R.string.archive_task_yes_dialog_button),
        onYes = onYes,
        onNo = onNo
    )
}
