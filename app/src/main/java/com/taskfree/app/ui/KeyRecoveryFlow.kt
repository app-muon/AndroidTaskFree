// KeyRecoveryFlow.kt

package com.taskfree.app.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.taskfree.app.Prefs
import com.taskfree.app.data.AppDatabaseFactory
import com.taskfree.app.enc.DatabaseKeyManager
import com.taskfree.app.ui.enc.PhraseEntry
import com.taskfree.app.ui.enc.RestorePrompt

@Composable
fun KeyRecoveryFlow(onFinished: () -> Unit) {
    val ctx = LocalContext.current
    var step by rememberSaveable { mutableStateOf(Step.PROMPT) }

    when (step) {
        Step.PROMPT -> RestorePrompt(
            onRestore = { step = Step.ENTRY },
            onSkip = {
                wipeEncryptedData(ctx)   // 🔸 clear everything & empty DB
                onFinished()             // ⬅️ AppNav will now render normally
            }
        )

        Step.ENTRY -> PhraseEntry(
            onCancel = { step = Step.PROMPT },
            onSuccess = {
                AppDatabaseFactory.clearInstance()
                onFinished()
            }
        )

        Step.DONE -> Unit      // unused now
    }
}

private fun wipeEncryptedData(ctx: Context) {
    // 1. delete the encrypted DB file
    ctx.getDatabasePath("checklists.db").delete()

    // 2. clear all encryption-related prefs and cached key
    Prefs.clearEncryption(ctx)          // removes "encrypted" flag + hash
    Prefs.clearEncryptionSecrets(ctx)   // removes derived key + salt + words
    DatabaseKeyManager.clearCachedKey()

    // 3. drop any open Room instance
    AppDatabaseFactory.clearInstance()
}
private enum class Step { PROMPT, ENTRY, DONE }
