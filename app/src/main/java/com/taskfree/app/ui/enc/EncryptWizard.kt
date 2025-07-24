package com.taskfree.app.ui.enc

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import com.taskfree.app.Prefs
import com.taskfree.app.R
import com.taskfree.app.data.AppDatabaseFactory
import com.taskfree.app.data.RealDatabaseMigrator
import com.taskfree.app.ui.components.ConfirmDialog
import com.taskfree.app.util.restartApp

/* ────────────────────────────────────────────────────────────────────────── *//* 1.  EncryptWizard – a modal overlay that runs the fake-encryption flow    *//* ────────────────────────────────────────────────────────────────────────── */

@Composable
fun EncryptWizard(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var step by rememberSaveable { mutableStateOf(WizardStep.INFO) }

    val progress by RealDatabaseMigrator.progress.collectAsState()
    /* ── if the user bails out before the migrator finishes, wipe any orphaned data ── */
    DisposableEffect(Unit) {
        onDispose {
            if (!Prefs.isEncrypted(ctx)) {
                // Encryption never completed, but keep phrase for retry
                Log.d("EncryptWizard", "Encryption incomplete - phrase preserved for retry")
            }
        }
    }

    when (step) {
        WizardStep.INFO -> EncryptInfo(
            onContinue = { step = WizardStep.PHRASE }, onCancel = onClose
        )

        WizardStep.PHRASE -> {
            val words = remember { MnemonicManager.getOrCreatePhrase(ctx) }
            EncryptPhraseScreen(
                words = words, onConfirmed = { step = WizardStep.PROGRESS },  onCancel = onClose
            )
        }

        WizardStep.PROGRESS -> {
            // Start real encryption process
            LaunchedEffect(Unit) {
                try {
                    val words = Prefs.loadPhrase(ctx) ?: return@LaunchedEffect
                    RealDatabaseMigrator.migrateToEncrypted(ctx, words)

                } catch (e: Exception) {
                    Log.e("EncryptWizard", "Encryption failed", e)
                }
            }

            EncryptProgress(progress)
            if (progress == 100) {
                LaunchedEffect(Unit) {
                    // Clear existing database instance to force recreation with encryption
                    AppDatabaseFactory.clearInstance()
                }
                step = WizardStep.SUCCESS
            }
        }

        WizardStep.SUCCESS -> EncryptSuccess(onDone = { ctx.restartApp() })
    }
}

private enum class WizardStep { INFO, PHRASE, PROGRESS, SUCCESS }

/* ────────────────────────────────────────────────────────────────────────── *//* 2.  ViewPhraseDialog – shows the saved 12-word phrase (debug build only)  *//* ────────────────────────────────────────────────────────────────────────── */

@Composable
fun ViewPhraseDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val words = Prefs.loadPhrase(ctx)

    val body = if (words == null) stringResource(R.string.no_phrase_found)
    else buildString {
        append(formatMnemonic(words, perRow = 3))
        appendLine()
        appendLine()
        append(stringResource(R.string.keep_your_8_word_phrase_safe))
    }

    ConfirmDialog(
        title = stringResource(R.string.your_recovery_phrase),
        message = body,
        yesMessage = stringResource(R.string.close),
        noMessage = "",                           // hide second button
        yesColour = colorResource(R.color.dialog_button_text_colour),
        onYes = onClose,
        onNo = onClose
    )
}
