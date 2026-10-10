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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.taskfree.app.Prefs
import com.taskfree.app.R
import com.taskfree.app.debugToast
import com.taskfree.app.ui.components.ConfirmDialog
import com.taskfree.app.ui.components.dialogMaxHeight
import com.taskfree.app.ui.components.dialogResponsiveWidth
import kotlinx.coroutines.launch

@Composable
fun RestorePrompt(
    onRestore: () -> Unit, onSkip: () -> Unit, error: String? = null
) {
    ConfirmDialog(
        title = stringResource(R.string.restore_found_title),
        message = stringResource(R.string.restore_found_body) + (error?.let { "\n\n$it" } ?: ""),
        yesMessage = stringResource(R.string.restore),
        noMessage = stringResource(R.string.skip),
        noColour = colorResource(R.color.bright_red),
        yesColour = colorResource(R.color.dialog_button_text_colour),
        onYes = onRestore,
        onNo = onSkip,
        onDismiss = {}
    )
}

/* --------------------------------------------------------------------- *//* 2. PhraseEntry – 8-word input & validation                            *//* --------------------------------------------------------------------- */
/** [onSubmit] receives the entered words and returns false to show [errorText]. */
@Composable
fun PhraseEntry(
    title: String,
    errorText: String,
    onSubmit: suspend (List<String>) -> Boolean,
    onCancel: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val inputs = remember { mutableStateListOf(*Array(8) { "" }) }
    var showError by remember { mutableStateOf(false) }
    var isValidating by remember { mutableStateOf(false) }

    /* dialog wrapper */
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = colorResource(R.color.dialog_background_colour)
            ),
            modifier = Modifier
                .dialogResponsiveWidth()
                .dialogMaxHeight()
        ) {

            /* header strip */
            Text(
                text = title,
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
                        text = errorText,
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
                            isValidating = true
                            showError = false
                            scope.launch {
                                if (!onSubmit(entered)) {
                                    isValidating = false
                                    showError = true
                                }
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

/** Restores the database key when [phrase] matches the stored hash. */
internal fun recoverDatabaseKey(context: Context, phrase: List<String>): Boolean {
    if (!validatePhraseAndRestoreDatabase(context, phrase)) return false
    // Save the phrase for future use
    Prefs.savePhrase(context, phrase)
    debugToast(context, "Phrase validated and database restored")
    return true
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
