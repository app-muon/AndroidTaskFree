package com.taskfree.app.ui.enc

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.taskfree.app.Prefs
import com.taskfree.app.R
import com.taskfree.app.debugToast
import com.taskfree.app.ui.components.ConfirmDialog

@Composable
fun RestorePrompt(
    onRestore: () -> Unit, onSkip: () -> Unit
) {
    ConfirmDialog(
        title = stringResource(R.string.restore_found_title),
        message = stringResource(R.string.restore_found_body),
        yesMessage = stringResource(R.string.restore),
        noMessage = stringResource(R.string.skip),
        noColour = colorResource(R.color.bright_red),
        yesColour = colorResource(R.color.dialog_button_text_colour),
        onYes = onRestore,
        onNo = onSkip
    )
}

/* --------------------------------------------------------------------- *//* 2. PhraseEntry – 8-word input & validation                            *//* --------------------------------------------------------------------- */
@Composable
fun PhraseEntry(
    onSuccess: () -> Unit,
    onCancel: () -> Unit
) {
    val ctx = LocalContext.current
    val inputs = remember { mutableStateListOf(*Array(8) { "" }) }
    var showError by remember { mutableStateOf(false) }
    var isValidating by remember { mutableStateOf(false) }

    /* dialog wrapper */
    Dialog(onDismissRequest = onCancel) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(R.color.dialog_background_colour)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {

            /* header strip */
            Text(
                text = stringResource(R.string.enter_phrase_title),
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

            /* body */
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState())
            ) {

                /** eight input rows (01-08) **/
                for (i in inputs.indices) {
                    OutlinedTextField(
                        value = inputs[i],
                        onValueChange = { inputs[i] = it },
                        label = { Text("%02d".format(i + 1)) },
                        singleLine = true,
                        enabled = !isValidating,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                }

                if (showError) {
                    Text(
                        text = stringResource(R.string.phrase_incorrect),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                if (isValidating) {
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Validating phrase...",
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                /* buttons */
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onCancel,
                        enabled = !isValidating,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = colorResource(R.color.dialog_button_text_colour)
                        )
                    ) {
                        Text(stringResource(R.string.cancel_no_dialog_button))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        enabled = inputs.all { it.isNotBlank() } && !isValidating,
                        onClick = {
                            val entered = inputs.map { it.trim().lowercase() }

                            // Validate the phrase by trying to use it as a key
                            isValidating = true
                            showError = false

                            if (validatePhraseAndRestoreDatabase(ctx, entered)) {
                                // Save the phrase for future use
                                Prefs.savePhrase(ctx, entered)
                                debugToast(ctx, "Phrase validated and database restored")
                                onSuccess()
                            } else {
                                isValidating = false
                                showError = true
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = colorResource(R.color.dialog_button_text_colour)
                        )
                    ) {
                        Text(stringResource(R.string.restore))
                    }
                }
            }
        }
    }
}

/**
 * Validates the phrase by checking it against the stored hash
 * and restores it if valid
 */
private fun validatePhraseAndRestoreDatabase(context: Context, phrase: List<String>): Boolean {
    return try {
        // Validate against the stored hash
        if (MnemonicManager.isPhraseValid(context, phrase)) {
            // Restore the phrase for future use
            MnemonicManager.storePhrase(context, phrase)
            true
        } else {
            false
        }
    } catch (e: Exception) {
        Log.e("RestoreFlow", "Phrase validation failed", e)
        false
    }
}