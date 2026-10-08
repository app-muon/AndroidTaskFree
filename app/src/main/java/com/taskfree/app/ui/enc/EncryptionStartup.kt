package com.taskfree.app.ui.enc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.taskfree.app.Prefs
import com.taskfree.app.R
import com.taskfree.app.data.*
import com.taskfree.app.ui.KeyRecoveryFlow
import com.taskfree.app.ui.components.ConfirmDialog
import com.taskfree.app.ui.components.dialogMaxHeight
import com.taskfree.app.ui.components.dialogResponsiveWidth

/** Returns true only when normal view models may be created. */
@Composable
internal fun EncryptionStartup(): Boolean {
    val ctx = LocalContext.current
    val state by RealDatabaseMigrator.startup.collectAsState()
    val outcome by RealDatabaseMigrator.outcome.collectAsState()
    val progress by RealDatabaseMigrator.progress.collectAsState()
    val conflict by RealDatabaseMigrator.conflict.collectAsState()
    val actionFailed by RealDatabaseMigrator.actionFailed.collectAsState()
    var showFeedback by rememberSaveable(state) { mutableStateOf(true) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, state) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) showFeedback = true
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { RealDatabaseMigrator.start(ctx) }
    if (state == DatabaseStartup.NEEDS_KEY) {
        KeyRecoveryFlow(onFinished = { RealDatabaseMigrator.retryRecovery(ctx) })
        return false
    }
    if (state != DatabaseStartup.READY) Box(
        Modifier.fillMaxSize().background(colorResource(R.color.todo_colour)),
        contentAlignment = Alignment.Center
    ) {
        if (state == DatabaseStartup.CHECKING) CircularProgressIndicator(color = Color.White)
        else TextButton(onClick = { showFeedback = true }) {
            Text(stringResource(R.string.encryption_failed_title), color = Color.White)
        }
    }
    when (state) {
        DatabaseStartup.MIGRATING -> EncryptProgress(progress)
        DatabaseStartup.RESTART -> ConfirmDialog(
            title = stringResource(R.string.encryption_restart_title),
            message = stringResource(R.string.encryption_restart_message),
            yesMessage = stringResource(R.string.restart_app), noMessage = "",
            onYes = { RealDatabaseMigrator.requestRestart(ctx) }, onNo = {}, onDismiss = {}
        )
        DatabaseStartup.CONFLICT -> if (showFeedback) conflict?.let {
            LegacySelectionDialog(it, onSelected = { choice -> RealDatabaseMigrator.retryRecovery(ctx, choice) },
                onDismiss = { showFeedback = false })
        }
        else -> if (state != DatabaseStartup.CHECKING && showFeedback &&
            (outcome != null || state == DatabaseStartup.BLOCKED || state == DatabaseStartup.FAILED)) {
            val blocked = state == DatabaseStartup.BLOCKED
            val cleanup = !blocked && outcome == Prefs.EncryptionOutcome.CLEANUP_WARNING
            ConfirmDialog(
                title = stringResource(if (cleanup) R.string.encryption_cleanup_title else R.string.encryption_failed_title),
                message = stringResource(when {
                    cleanup -> R.string.encryption_cleanup_warning
                    blocked -> R.string.encryption_recovery_blocked
                    else -> R.string.encryption_rolled_back
                }) + if (actionFailed) "\n\n" + stringResource(R.string.encryption_action_failed) else "",
                yesMessage = stringResource(if (cleanup) R.string.retry_cleanup else if (blocked)
                    R.string.retry_recovery else R.string.retry_encryption),
                noMessage = stringResource(if (state == DatabaseStartup.READY || blocked)
                    R.string.got_it_confirmation else R.string.restart_app),
                onYes = {
                    if (cleanup || blocked) RealDatabaseMigrator.retryRecovery(ctx)
                    else Prefs.loadPhrase(ctx)?.let { RealDatabaseMigrator.requestEncryption(ctx, it) }
                },
                onNo = {
                    // A blocked outcome is cleared only by verified recovery, so retries re-check integrity.
                    if (blocked) showFeedback = false
                    else if (RealDatabaseMigrator.acknowledgeOutcome(ctx)) {
                        showFeedback = false
                        if (state == DatabaseStartup.FAILED) RealDatabaseMigrator.requestRestart(ctx)
                    }
                },
                onDismiss = { showFeedback = false }
            )
        }
    }
    return state == DatabaseStartup.READY
}

@Composable
internal fun LegacySelectionDialog(conflict: LegacyConflict, onSelected: (LegacyChoice) -> Unit, onDismiss: () -> Unit) {
    var selection by rememberSaveable(conflict.main?.tasks, conflict.backup?.tasks) { mutableStateOf(conflict.suggested) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(Modifier.dialogResponsiveWidth().dialogMaxHeight(), shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = colorResource(R.color.dialog_background_colour))) {
            Column {
                Text(stringResource(R.string.encryption_conflict_title), color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.fillMaxWidth().background(colorResource(R.color.dialog_primary_colour)).padding(24.dp))
                Text(stringResource(R.string.encryption_conflict_message), modifier = Modifier.padding(24.dp),
                    color = colorResource(R.color.surface_colour))
                listOf(LegacyChoice.MAIN to conflict.main, LegacyChoice.BACKUP to conflict.backup).forEach { (choice, candidate) ->
                    val label = stringResource(if (choice == LegacyChoice.MAIN) R.string.encryption_main_copy else R.string.encryption_backup_copy)
                    Row(Modifier.fillMaxWidth().selectable(selected = selection == choice, enabled = candidate != null,
                        onClick = { selection = choice }).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selection == choice, onClick = { selection = choice }, enabled = candidate != null)
                        Text(label + "\n" + if (candidate == null) stringResource(R.string.encryption_copy_unavailable)
                            else stringResource(R.string.encryption_copy_counts, candidate.tasks, candidate.categories),
                            color = colorResource(R.color.surface_colour))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(
                        contentColor = colorResource(R.color.dialog_button_text_colour))) {
                        Text(stringResource(R.string.cancel_no_dialog_button))
                    }
                    TextButton(enabled = selection != null, onClick = { selection?.let(onSelected) },
                        colors = ButtonDefaults.textButtonColors(contentColor = colorResource(R.color.dark_red))) {
                        Text(stringResource(R.string.encryption_use_selected))
                    }
                }
            }
        }
    }
}
