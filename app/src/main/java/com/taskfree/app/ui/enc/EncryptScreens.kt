package com.taskfree.app.ui.enc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.taskfree.app.R
import com.taskfree.app.debugToast
import com.taskfree.app.ui.components.AppCheckbox
import com.taskfree.app.ui.components.ConfirmDialog

/* 1 ▸ info */
@Composable
fun EncryptInfo(onContinue: () -> Unit, onCancel: () -> Unit) {

    ConfirmDialog(
        title = stringResource(R.string.encrypt_my_data_command),
        message = stringResource(R.string.you_ll_need_to_enter),
        yesMessage = stringResource(R.string.encrypt_yes_dialog_button),
        onYes = onContinue,
        onNo = onCancel
    )
}

/* 2 ▸ phrase display */
@Composable
fun EncryptPhraseScreen(
    words: List<String>, onConfirmed: () -> Unit, onCancel: () -> Unit
) {
    var chk1 by remember { mutableStateOf(false) }
    var chk2 by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    Dialog(onDismissRequest = onCancel) {
        Card(
            shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(
                containerColor = colorResource(R.color.dialog_background_colour)
            ), modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                /* header strip */
                Text(
                    text = stringResource(R.string.your_recovery_phrase),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = colorResource(R.color.dialog_primary_colour),
                            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                        )
                        .padding(24.dp)
                )

                /* phrase block */
                Spacer(Modifier.height(16.dp))

                Text(
                    formatMnemonic(words, perRow = 3),
                    fontFamily = FontFamily.Monospace,
                    color = colorResource(R.color.surface_colour)
                )
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppCheckbox(chk1) { chk1 = it }
                    Text(
                        stringResource(R.string.i_ve_written_the_8_words),
                        color = colorResource(R.color.surface_colour)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppCheckbox(chk2) { chk2 = it }
                    Text(
                        stringResource(R.string.i_understand_i_can_t_recover_without_them),
                        color = colorResource(R.color.surface_colour)
                    )
                }
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onCancel,
                        colors = ButtonDefaults.textButtonColors(contentColor = colorResource(R.color.dialog_button_text_colour))
                    ) {
                        Text(stringResource(R.string.cancel_no_dialog_button))
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        enabled = chk1 && chk2,
                        onClick = { debugToast(ctx, "Phrase confirmed"); onConfirmed() },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = colorResource(R.color.dark_red)
                        )
                    ) { Text(stringResource(R.string.encrypt_yes_dialog_button)) }
                }
            }
        }
    }
}

/* 3 ▸ progress */
@Composable
fun EncryptProgress(progress: Int) {
    Dialog(onDismissRequest = {}) {
        Card(
            shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(
                containerColor = colorResource(R.color.dialog_background_colour)
            )
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .widthIn(min = 220.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.encrypting_header),
                    style = MaterialTheme.typography.headlineSmall,
                    color = colorResource(R.color.surface_colour)
                )
                Spacer(Modifier.height(16.dp))
                if (progress < 0) CircularProgressIndicator()
                else {
                    CircularProgressIndicator(
                        progress = { progress / 100f }
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.encryption_progress_percentage, progress),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorResource(R.color.surface_colour)
                    )
                }
            }
        }
    }
}

/* 4 ▸ success */
@Composable
fun EncryptSuccess(onDone: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.data_encrypted),
        message = stringResource(R.string.keep_your_8_word_phrase_safe),
        yesMessage = stringResource(R.string.got_it_confirmation),
        yesColour = colorResource(R.color.dialog_button_text_colour),
        onYes = onDone,
        onNo = onDone
    )
}
