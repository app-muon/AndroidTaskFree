package com.taskfree.app.ui.admin

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.taskfree.app.Prefs
import com.taskfree.app.R
import com.taskfree.app.data.repository.Backup
import com.taskfree.app.data.repository.BackupManager
import com.taskfree.app.data.repository.BackupManager.BackupValidationException
import com.taskfree.app.data.repository.BackupSource
import com.taskfree.app.ui.components.ConfirmDialog
import com.taskfree.app.ui.enc.PhraseEntry
import com.taskfree.app.util.restartApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant

internal sealed interface RestoreStep {
    class NeedsPhrase(val source: BackupSource.Encrypted) : RestoreStep
    class Confirm(val backup: Backup, val encrypted: Boolean, val enteredPhrase: List<String>? = null) : RestoreStep
}

/** Reads the chosen file. An encrypted one is unlocked with this phone's phrase when that works. */
internal suspend fun openBackup(ctx: Context, uri: Uri): RestoreStep =
    when (val source = BackupManager.read(ctx, uri)) {
        is BackupSource.Plain -> RestoreStep.Confirm(source.backup, encrypted = false)
        is BackupSource.Encrypted -> Prefs.loadPhrase(ctx)
            ?.let { BackupManager.unlock(source, it) }
            ?.let { RestoreStep.Confirm(it, encrypted = true) }
            ?: RestoreStep.NeedsPhrase(source)
    }

internal fun showRestoreError(ctx: Context, e: Throwable) {
    val message = if (e is BackupValidationException) ctx.getString(e.resId, *e.args)
    else ctx.getString(R.string.err_generic_restore)
    Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
}

/** Asks for the backup's phrase when needed, then confirms before replacing everything. */
@Composable
internal fun RestoreDialogs(vm: ToolsViewModel, step: RestoreStep?, onStep: (RestoreStep?) -> Unit) {
    val ctx = LocalContext.current
    // Outlives the dialogs, so closing the confirmation doesn't cancel the restore.
    val scope = rememberCoroutineScope()
    when (step) {
        is RestoreStep.NeedsPhrase -> PhraseEntry(
            title = stringResource(R.string.restore_phrase_title),
            errorText = stringResource(R.string.restore_phrase_wrong),
            onSubmit = { phrase -> submitPhrase(ctx, step.source, phrase, onStep) },
            onCancel = { onStep(null) }
        )
        is RestoreStep.Confirm -> ConfirmDialog(
            title = stringResource(R.string.restore_confirm_title),
            message = restoreSummary(step),
            yesMessage = stringResource(R.string.restore_replace),
            yesColour = colorResource(R.color.bright_red),
            onYes = {
                onStep(null)
                scope.launch { restore(ctx, vm, step) }
            },
            onNo = { onStep(null) }
        )
        null -> {}
    }
}

/** Returns false only when [phrase] doesn't unlock the file. */
private suspend fun submitPhrase(
    ctx: Context, source: BackupSource.Encrypted, phrase: List<String>, onStep: (RestoreStep?) -> Unit
): Boolean {
    val backup = try {
        BackupManager.unlock(source, phrase) ?: return false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) { // Unlocked, but the contents are not a valid backup.
        showRestoreError(ctx, e)
        onStep(null)
        return true
    }
    onStep(RestoreStep.Confirm(backup, encrypted = true, enteredPhrase = phrase))
    return true
}

private suspend fun restore(ctx: Context, vm: ToolsViewModel, step: RestoreStep.Confirm) {
    runCatching { vm.restoreBackup(step.backup) }.onSuccess {
        // Best effort: without it, later backups just start with a new phrase.
        step.enteredPhrase?.let { runCatching { Prefs.keepRestoredPhrase(ctx, it) } }
        Toast.makeText(ctx, R.string.restore_ok, Toast.LENGTH_LONG).show()
        ctx.restartApp()
    }.onFailure { showRestoreError(ctx, it) }
}

@Composable
private fun restoreSummary(step: RestoreStep.Confirm): String {
    val backup = step.backup
    val savedAt = runCatching { Instant.parse(backup.exported_at) }.getOrNull()
    val categories = backup.categories.count { !it.isDeleted }
    val details = listOfNotNull(
        savedAt?.let { stringResource(R.string.restore_saved_at, formatBackupTime(it)) },
        pluralStringResource(R.plurals.backup_category_count, categories, categories) + " · " +
            pluralStringResource(R.plurals.backup_task_count, backup.tasks.size, backup.tasks.size),
        if (step.encrypted) null else stringResource(R.string.restore_unencrypted_note)
    )
    return details.joinToString("\n") + "\n\n" + stringResource(R.string.restore_replace_warning)
}
