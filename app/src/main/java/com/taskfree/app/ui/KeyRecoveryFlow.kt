package com.taskfree.app.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.taskfree.app.R
import com.taskfree.app.data.RealDatabaseMigrator
import com.taskfree.app.ui.enc.PhraseEntry
import com.taskfree.app.ui.enc.RestorePrompt

@Composable
fun KeyRecoveryFlow(onFinished: () -> Unit) {
    val ctx = LocalContext.current
    var enterPhrase by rememberSaveable { mutableStateOf(false) }
    val failed by RealDatabaseMigrator.actionFailed.collectAsState()
    if (enterPhrase) PhraseEntry(
        onCancel = { enterPhrase = false },
        onSuccess = onFinished
    ) else RestorePrompt(
        onRestore = { enterPhrase = true },
        onSkip = { RealDatabaseMigrator.skipRestoredDatabase(ctx) },
        error = if (failed) stringResource(R.string.encryption_reset_failed) else null
    )
}