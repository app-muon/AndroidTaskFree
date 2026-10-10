// ToolsMenuDialog.kt
package com.taskfree.app.ui.admin

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import com.taskfree.app.BuildConfig
import com.taskfree.app.Prefs
import com.taskfree.app.R
import com.taskfree.app.data.AppDatabaseFactory
import com.taskfree.app.data.repository.CompletedRepeatsPreview
import com.taskfree.app.debugToast
import com.taskfree.app.enc.DatabaseKeyManager
import com.taskfree.app.ui.components.ActionItem
import com.taskfree.app.ui.components.ConfirmArchive
import com.taskfree.app.ui.components.ConfirmDeletion
import com.taskfree.app.ui.components.ConfirmDialog
import com.taskfree.app.ui.components.PanelActionList
import com.taskfree.app.ui.components.PanelConstants
import com.taskfree.app.ui.theme.providePanelColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var repeatsPreview by remember { mutableStateOf<CompletedRepeatsPreview?>(null) }
    var showContact by rememberSaveable { mutableStateOf(false) }
    var showPrivacyPolicy by rememberSaveable { mutableStateOf(false) }
    var showTextSize by rememberSaveable { mutableStateOf(false) }
    var showBackup by rememberSaveable { mutableStateOf(false) }
    val colors = providePanelColors()
    val ctx = LocalContext.current
    val encrypted = Prefs.isEncrypted(ctx)
    val scope = rememberCoroutineScope()
    // This host stays composed while its menu and confirmation are closed.
    LaunchedEffect(vm, ctx) {
        // Events are sent from IO; Toast needs a Looper thread whatever dispatcher runs this effect.
        withContext(Dispatchers.Main) { vm.events.collect { event ->
            val message = when (event) {
                is ToolsEvent.Archived -> ctx.resources.getQuantityString(
                    R.plurals.archived_task_count, event.count, event.count
                )
                ToolsEvent.Failed -> ctx.getString(R.string.bulk_action_failed)
                ToolsEvent.Deleted -> null
            }
            message?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
        } }
    }
    if (show) {
        PanelActionList(
            headerContent = {
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
            }, actions = listOfNotNull(
                ActionItem(
                    label = stringResource(R.string.text_size_title),
                    icon = Icons.Filled.TextFields,
                    onClick = {
                        showTextSize = true
                        onDismiss()
                    }
                ),
                ActionItem(
                    label = stringResource(R.string.tip_show_tips_again),
                    icon = Icons.Default.Refresh,
                    onClick = {
                        onResetTips()
                        onDismiss()
                    }),
                ActionItem(
                    label = stringResource(R.string.contact_title),
                    icon = Icons.Filled.Email,
                    onClick = {
                        showContact = true
                        onDismiss()
                    }
                ),
                ActionItem(
                    label = stringResource(R.string.privacy_policy_title),
                    icon = Icons.Filled.PrivacyTip,
                    onClick = {
                        showPrivacyPolicy = true
                        onDismiss()
                    }
                ),
                ActionItem(
                    labelContent = { BackupMenuLabel() },
                    icon = Icons.Outlined.Backup,
                    onClick = {
                        showBackup = true
                        onDismiss()
                    }), if (BuildConfig.DEBUG) {
                    ActionItem(
                        label = "Reset encryption (debug)",
                        icon = Icons.Default.Refresh,
                        onClick = {
                            val appContext = ctx.applicationContext
                            scope.launch {
                                // Blocking gate and file work stays off the main thread.
                                val reset = runCatching {
                                    withContext(Dispatchers.IO) {
                                        com.taskfree.app.data.RealDatabaseMigrator.resetEncryption(appContext)
                                    }
                                }
                                reset.onSuccess { com.taskfree.app.data.RealDatabaseMigrator.requestRestart(appContext) }
                                    .onFailure {
                                        android.util.Log.e("ToolsMenuDialog", "Debug encryption reset failed", it)
                                        Toast.makeText(appContext, "Reset failed: ${it.message}", Toast.LENGTH_LONG).show()
                                    }
                            }
                            onDismiss()
                        })
                } else null, ActionItem(
                    label = if (encrypted) stringResource(R.string.view_recovery_phrase)
                    else stringResource(R.string.encrypt_my_data_command),
                    icon = Icons.Default.Lock,
                    onClick = {
                        onDismiss()
                        if (encrypted) showPhraseFlow() else startEncryptFlow()
                    }), ActionItem(
                    label = stringResource(R.string.archive_old_completed),
                    icon = Icons.Default.Archive,
                    iconTint = colors.darkRed,
                    onClick = {
                        pending = PendingAction.ARCHIVE
                        onDismiss()
                    }), ActionItem(
                    label = stringResource(R.string.archive_old_completed_repeats),
                    icon = Icons.Default.Archive,
                    iconTint = colors.darkRed,
                    onClick = {
                        onDismiss()
                        scope.launch {
                            repeatsPreview = try {
                                vm.completedRepeatsPreview()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.e("ToolsMenuDialog", "Loading completed repeats failed", e)
                                Toast.makeText(ctx, R.string.bulk_action_failed, Toast.LENGTH_SHORT).show()
                                null
                            }
                        }
                    }), ActionItem(
                    label = stringResource(R.string.permanently_delete_tasks),
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

    if (showContact) {
        ConfirmDialog(
            title = stringResource(R.string.contact_title),
            message = stringResource(R.string.tip_contact_body),
            yesMessage = stringResource(R.string.close),
            yesColour = colorResource(R.color.dialog_button_text_colour),
            noMessage = "",                 // hide second button
            onYes = { showContact = false },
            onNo = { showContact = false }
        )
    }

    if (showPrivacyPolicy) {
        ConfirmDialog(
            title = stringResource(R.string.privacy_policy_title),
            message = stringResource(R.string.privacy_policy_body),
            yesMessage = stringResource(R.string.close),
            yesColour = colorResource(R.color.dialog_button_text_colour),
            noMessage = "",
            onYes = { showPrivacyPolicy = false },
            onNo = { showPrivacyPolicy = false }
        )
    }

    if (showTextSize) {
        TextSizeDialog(onClose = { showTextSize = false })
    }

    BackupFileDialog(vm, show = showBackup, onDismiss = { showBackup = false })

    repeatsPreview?.let { preview ->
        ArchiveRepeatsDialog(
            preview = preview,
            onConfirm = {
                vm.archiveOldCompletedRepeats(asOf = preview.asOf)
                repeatsPreview = null
            },
            onDismiss = { repeatsPreview = null }
        )
    }

    if (pending != null) {
        val action = pending!!

        if (action == PendingAction.ARCHIVE) {
            ConfirmArchive(
                title = stringResource(R.string.confirm_archived_completed_title),
                message = stringResource(R.string.confirm_archive_old_completed_msg),
                onYes = {
                    vm.archiveOldCompleted()
                    pending = null
                },
                onNo = { pending = null })
        }

        if (action == PendingAction.PERMANENTLY_DELETE) {
            ConfirmDeletion(
                title = stringResource(R.string.confirm_permanently_delete_title),
                message = stringResource(R.string.this_action_cannot_be_undone),
                onYes = {
                    vm.deleteArchived()
                    pending = null
                },
                onNo = { pending = null })
        }
    }
}

private enum class PendingAction { ARCHIVE, PERMANENTLY_DELETE }
