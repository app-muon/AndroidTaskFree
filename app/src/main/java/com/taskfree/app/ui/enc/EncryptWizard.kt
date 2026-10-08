package com.taskfree.app.ui.enc

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.taskfree.app.Prefs
import com.taskfree.app.R
import com.taskfree.app.data.RealDatabaseMigrator
import com.taskfree.app.ui.components.ConfirmDialog
import com.taskfree.app.ui.components.dialogMaxHeight
import com.taskfree.app.ui.components.dialogResponsiveWidth

/** Phrase confirmation only requests a fresh process; this composition never owns migration. */
@Composable
fun EncryptWizard(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var showPhrase by rememberSaveable { mutableStateOf(false) }
    var failed by rememberSaveable { mutableStateOf(false) }
    if (failed) {
        ConfirmDialog(
            title = stringResource(R.string.encryption_failed_title),
            message = stringResource(R.string.encryption_request_failed),
            yesMessage = stringResource(R.string.got_it_confirmation),
            noMessage = "",
            onYes = { failed = false }, onNo = {}, onDismiss = { failed = false }
        )
    } else if (showPhrase) {
        val words = remember { MnemonicManager.getOrCreatePhrase(ctx) }
        EncryptPhraseScreen(words, onConfirmed = {
            try { failed = !RealDatabaseMigrator.requestEncryption(ctx, words) }
            catch (e: Exception) {
                Log.e("EncryptWizard", "Could not request encryption", e)
                failed = true
            }
        }, onCancel = onClose)
    } else EncryptInfo(onContinue = { showPhrase = true }, onCancel = onClose)
}

/* ────────────────────────────────────────────────────────────────────────── *//* 2.  ViewPhraseDialog – shows the saved 12-word phrase (debug build only)  *//* ────────────────────────────────────────────────────────────────────────── */

@Composable
fun ViewPhraseDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val words = Prefs.loadPhrase(ctx)

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .dialogResponsiveWidth()   // 100% phone, ~80% tablet
                .dialogMaxHeight(),          // cap by window height
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = colorResource(R.color.dialog_background_colour))
        ) {
            Column {
                // Header
                Text(
                    text = stringResource(R.string.your_recovery_phrase),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = colorResource(R.color.dialog_primary_colour),
                            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                        )
                        .padding(24.dp)
                )

                if (words.isNullOrEmpty()) {
                    Text(
                        text = stringResource(R.string.no_phrase_found),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorResource(R.color.surface_colour),
                        modifier = Modifier.padding(24.dp)
                    )
                } else {
                    MnemonicList(
                        words = words,
                        perRow = 3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)   // content scroll bound inside dialog
                    )

                    Text(
                        text = stringResource(R.string.keep_your_8_word_phrase_safe),
                        style = MaterialTheme.typography.labelSmall,
                        color = colorResource(R.color.surface_colour),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
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
private fun MnemonicList(
    words: List<String>,
    perRow: Int,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)
    ) {
        itemsIndexed(words.chunked(perRow)) { rowIdx, row ->
            SelectionContainer {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEachIndexed { i, w ->
                        val index = rowIdx * perRow + i
                        Text(
                            text = "${(index + 1).toString().padStart(2, ' ')}. $w",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = colorResource(R.color.surface_colour),
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
            }
        }
    }
}
