// ToolsMenuDialog.kt
package com.taskfree.app.ui.admin

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.taskfree.app.BuildConfig
import com.taskfree.app.Prefs
import com.taskfree.app.R
import com.taskfree.app.data.AppDatabaseFactory
import com.taskfree.app.data.repository.BackupManager
import com.taskfree.app.debugToast
import com.taskfree.app.enc.DatabaseKeyManager
import com.taskfree.app.ui.components.ActionItem
import com.taskfree.app.ui.components.ConfirmArchive
import com.taskfree.app.ui.components.ConfirmDeletion
import com.taskfree.app.ui.components.ConfirmDialog
import com.taskfree.app.ui.components.PanelActionList
import com.taskfree.app.ui.components.PanelConstants
import com.taskfree.app.ui.theme.providePanelColors
import com.taskfree.app.util.restartApp
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter


@Composable
fun ToolsMenuDialog(
    vm: ToolsViewModel,
    show: Boolean,
    startEncryptFlow: () -> Unit,
    showPhraseFlow: () -> Unit,
    onDismiss: () -> Unit,
    onResetTips: () -> Unit
) {
    val isOn = vm.uiState.collectAsState().value.showArchived
    var pending by remember { mutableStateOf<PendingAction?>(null) }
    val colors = providePanelColors()
    val ctx = LocalContext.current
    val encrypted = Prefs.isEncrypted(ctx)
    val scope = rememberCoroutineScope()
    val saveLauncher = rememberLauncherForActivityResult(
        CreateDocument("application/json")
    ) { uri ->
        if (uri != null) scope.launch {
            runCatching { vm.buildBackup() }.onSuccess { bytes ->
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                Toast.makeText(ctx, R.string.backup_ok, Toast.LENGTH_LONG).show()
            }.onFailure { e ->
                Toast.makeText(
                    ctx,
                    e.message ?: ctx.getString(R.string.backup_save_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /* — Open launcher — */
    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            runCatching { vm.importBackup(ctx, uri) }.onSuccess {

                Toast.makeText(ctx, R.string.restore_ok, Toast.LENGTH_LONG).show()
                ctx.restartApp()

            }.onFailure { e ->
                val msg = if (e is BackupManager.BackupValidationException)
                    ctx.getString(e.resId, *e.args)
                else ctx.getString(R.string.err_generic_restore)
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            }
        }
    }
    if (show) {
        PanelActionList(headerContent = {
            Column(
                modifier = Modifier.padding(
                    horizontal = PanelConstants.HORIZONTAL_PADDING,
                    vertical = PanelConstants.SECTION_VERTICAL_PADDING
                )
            ) {
                Text(
                    text = stringResource(R.string.tools_menu_name),
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.surfaceText
                )
                Text(
                    text = BuildConfig.VERSION_NAME,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.surfaceText
                )
            }
        }, actions = listOfNotNull(ActionItem(label = stringResource(R.string.backup_to_file),
            icon = Icons.Outlined.Backup,
            onClick = {
                val name = "taskapp_backup_" + LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm")) + ".json"
                saveLauncher.launch(name)
                onDismiss()
            }), ActionItem(label = stringResource(R.string.restore_from_file),
            icon = Icons.Outlined.Restore,
            onClick = {
                pending = PendingAction.RESTORE
                onDismiss()
            }), if (BuildConfig.DEBUG) {
            ActionItem(label = "Reset encryption (debug)",
                icon = Icons.Default.Refresh,
                onClick = {
                    Prefs.clearEncryption(ctx)
                    Prefs.clearEncryptionSecrets(ctx)
                    DatabaseKeyManager.clearCachedKey()
                    AppDatabaseFactory.clearInstance()
                    listOf("checklists.db", "checklists_temp.db", "checklists_backup.db")
                        .forEach { name ->
                            ctx.getDatabasePath(name)?.takeIf { it.exists() }?.delete()
                        }
                    debugToast(ctx, "Encryption reset completely and data deleted")
                    onDismiss()
                })
        } else null, ActionItem(label = if (encrypted) stringResource(R.string.view_recovery_phrase)
        else stringResource(R.string.encrypt_my_data_command),
            icon = Icons.Default.Lock,
            onClick = {
                onDismiss()
                if (encrypted) showPhraseFlow() else startEncryptFlow()
            }), ActionItem(label = stringResource(R.string.tip_show_tips_again),
            icon = Icons.Default.Refresh,
            onClick = {
                onResetTips()
                onDismiss()
            }), ActionItem(label = stringResource(R.string.archive_old_completed),
            icon = Icons.Default.Archive,
            iconTint = colors.darkRed,
            onClick = {
                pending = PendingAction.ARCHIVE
                onDismiss()
            }), ActionItem(label = stringResource(R.string.permanently_delete_tasks),
            icon = Icons.Default.Delete,
            iconTint = colors.brightRed,
            onClick = {
                pending = PendingAction.PERMANENTLY_DELETE
                onDismiss()
            }), ActionItem(labelContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                val label = if (isOn) {
                    stringResource(R.string.toggle_view_unarchived)
                } else {
                    stringResource(R.string.toggle_view_archived)
                }
                Text(label, color = colors.surfaceText)
                Switch(checked = isOn, onCheckedChange = { vm.toggleShowArchived() })
            }
        }, icon = Icons.Default.Visibility, onClick = { vm.toggleShowArchived() })
        ), onDismiss = onDismiss
        )
    }

    if (pending != null) {
        val action = pending!!

        if (action == PendingAction.ARCHIVE) {
            ConfirmArchive(title = stringResource(R.string.confirm_archived_completed_title),
                message = stringResource(R.string.confirm_archive_old_completed_msg),
                onYes = {
                    vm.archiveOldCompleted()
                    pending = null
                },
                onNo = { pending = null })
        }

        if (action == PendingAction.PERMANENTLY_DELETE) {
            ConfirmDeletion(title = stringResource(R.string.confirm_permanently_delete_title),
                message = stringResource(R.string.this_action_cannot_be_undone),
                onYes = {
                    vm.deleteArchived()
                    pending = null
                },
                onNo = { pending = null })
        }
        if (action == PendingAction.RESTORE) {
            ConfirmDialog(
                title = stringResource(R.string.confirm_restore_title),
                message = stringResource(R.string.confirm_restore_msg),
                yesMessage = stringResource(R.string.restore_yes_dialog_button),
                onYes = {
                    openLauncher.launch(arrayOf("application/json"))
                    pending = null
                },
                onNo = { pending = null }
            )
        }
    }
}

private enum class PendingAction { ARCHIVE, PERMANENTLY_DELETE, RESTORE }