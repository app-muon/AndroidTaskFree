package com.taskfree.app.ui.admin

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.taskfree.app.Prefs
import com.taskfree.app.R
import com.taskfree.app.data.backup.AutoBackup
import com.taskfree.app.data.backup.BackupError
import com.taskfree.app.data.backup.BackupStatus
import com.taskfree.app.data.backup.isPhoneStorage
import com.taskfree.app.data.repository.BackupManager.BackupValidationException
import com.taskfree.app.ui.components.ActionItem
import com.taskfree.app.ui.components.ConfirmDialog
import com.taskfree.app.ui.components.PanelActionList
import com.taskfree.app.ui.components.PanelConstants
import com.taskfree.app.ui.components.formatDateTime
import com.taskfree.app.ui.enc.EncryptPhraseScreen
import com.taskfree.app.ui.enc.MnemonicManager
import com.taskfree.app.ui.enc.ViewPhraseDialog
import com.taskfree.app.ui.theme.providePanelColors
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private sealed interface BackupStep {
    /** The database is encrypted but its phrase isn't on this phone; a new phrase wouldn't match it. */
    data object NoPhrase : BackupStep
    /** Confirms the phrase backups use, then picks a folder or accepts the phrase. */
    data class ShowPhrase(val thenPickFolder: Boolean) : BackupStep
    data class CloudWarning(val uri: Uri) : BackupStep
    data object ViewPhrase : BackupStep
    data object ConfirmTurnOff : BackupStep
    data object ConfirmExport : BackupStep
}

/** The "Backup files" panel and its flows. It stays composed while the panel is closed. */
@Composable
fun BackupFileDialog(vm: ToolsViewModel, show: Boolean, onShow: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = providePanelColors()
    val status by AutoBackup.status(ctx).collectAsState()
    var step by remember { mutableStateOf<BackupStep?>(null) }
    var restoreStep by remember { mutableStateOf<RestoreStep?>(null) }

    fun showSetupResult(result: AutoBackup.EnableResult) {
        val message = when (result) {
            AutoBackup.EnableResult.STARTED -> { onShow(); return }
            AutoBackup.EnableResult.BUSY -> R.string.backup_setup_busy
            AutoBackup.EnableResult.NOT_READY -> R.string.backup_setup_not_ready
            AutoBackup.EnableResult.RESTART_REQUIRED -> R.string.backup_setup_restart
        }
        Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
    }
    fun enableFolder(uri: Uri) = showSetupResult(AutoBackup.enable(ctx, uri))

    val pickFolder = rememberLauncherForActivityResult(OpenDocumentTree()) { uri ->
        if (uri != null) {
            val rejection = AutoBackup.setupRejection(ctx)
            when {
                rejection != null -> showSetupResult(rejection)
                uri.isPhoneStorage() -> enableFolder(uri)
                else -> step = BackupStep.CloudWarning(uri)
            }
        }
    }
    val openFile = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { openBackup(ctx, uri) }
                .onSuccess { restoreStep = it }
                .onFailure { showRestoreError(ctx, it) }
        }
    }
    val exportFile = rememberLauncherForActivityResult(CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                vm.exportBackup(ctx.contentResolver, uri)
            }.onSuccess {
                Toast.makeText(ctx, R.string.backup_ok, Toast.LENGTH_LONG).show()
            }.onFailure { e ->
                if (e is CancellationException) throw e
                val message = if (e is BackupValidationException) ctx.getString(e.resId, *e.args)
                    else ctx.getString(R.string.backup_save_failed)
                Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun phraseUnavailable() = Prefs.isEncrypted(ctx) && Prefs.loadPhrase(ctx) == null
    fun chooseFolder() {
        when {
            phraseUnavailable() -> step = BackupStep.NoPhrase
            Prefs.loadPhrase(ctx) == null -> step = BackupStep.ShowPhrase(thenPickFolder = true)
            else -> pickFolder.launch(null)
        }
    }
    fun reviewPhrase() {
        step = if (phraseUnavailable()) BackupStep.NoPhrase else BackupStep.ShowPhrase(thenPickFolder = false)
    }

    if (show) {
        val settings = status.settings
        val actions = buildList {
            if (settings == null) {
                add(ActionItem(label = stringResource(R.string.backup_set_up), icon = Icons.Outlined.Backup,
                    enabled = status.canMutate,
                    onClick = { chooseFolder() }))
            } else {
                val folderAction = when {
                    settings.needsMigration || status.error?.kind in
                        listOf(BackupError.Kind.ACTIVE_DAMAGED, BackupError.Kind.FAILED) ->
                        R.string.backup_choose_folder
                    status.error?.kind == BackupError.Kind.NO_ACCESS -> R.string.backup_choose_again
                    else -> R.string.backup_change_file
                }
                add(ActionItem(label = stringResource(folderAction), enabled = status.canMutate,
                    icon = Icons.Outlined.FolderOpen, onClick = { chooseFolder() }))
                // Stays open, so the status above shows the save happen.
                add(ActionItem(label = stringResource(R.string.backup_now), icon = Icons.Outlined.Backup,
                    enabled = status.canMutate && !settings.needsMigration, dismissOnClick = false,
                    onClick = { AutoBackup.backUpNow(ctx) }))
                add(ActionItem(label = stringResource(R.string.backup_turn_off), icon = Icons.Outlined.CloudOff,
                    enabled = status.canMutate,
                    onClick = { step = BackupStep.ConfirmTurnOff }))
            }
            if (status.error?.kind == BackupError.Kind.PHRASE_CHANGED ||
                status.operationError?.kind == BackupError.Kind.PHRASE_CHANGED)
                add(ActionItem(label = stringResource(R.string.backup_review_phrase), enabled = status.canMutate,
                    icon = Icons.Outlined.Warning, iconTint = colors.brightRed, onClick = { reviewPhrase() }))
            if (settings != null || Prefs.loadPhrase(ctx) != null)
                add(ActionItem(label = stringResource(R.string.view_recovery_phrase), icon = Icons.Outlined.Lock,
                    onClick = { step = BackupStep.ViewPhrase }))
            add(ActionItem(label = stringResource(R.string.restore_from_file), icon = Icons.Outlined.Restore,
                onClick = { openFile.launch(arrayOf("*/*")) }))
            add(ActionItem(label = stringResource(R.string.backup_export_unencrypted), icon = Icons.Outlined.FileDownload,
                onClick = { step = BackupStep.ConfirmExport }))
        }
        PanelActionList(headerContent = { BackupHeader(status) }, actions = actions, onDismiss = onDismiss)
    }

    when (val current = step) {
        BackupStep.NoPhrase -> ConfirmDialog(
            title = stringResource(R.string.backup_no_phrase_title),
            message = stringResource(R.string.backup_no_phrase_msg),
            yesMessage = stringResource(R.string.close),
            yesColour = colorResource(R.color.dialog_button_text_colour),
            noMessage = "",
            onYes = { step = null },
            onNo = { step = null }
        )
        is BackupStep.ShowPhrase -> {
            val words = remember { MnemonicManager.getOrCreatePhrase(ctx) }
            EncryptPhraseScreen(
                words = words,
                confirmLabel = stringResource(R.string.backup_continue),
                confirmEnabled = status.canMutate,
                onConfirmed = {
                    step = null
                    if (current.thenPickFolder) pickFolder.launch(null) else AutoBackup.usePhrase(ctx)
                },
                onCancel = { step = null }
            )
        }
        is BackupStep.CloudWarning -> ConfirmDialog(
            title = stringResource(R.string.backup_cloud_title),
            message = stringResource(R.string.backup_cloud_msg),
            yesMessage = stringResource(R.string.backup_use_anyway),
            noMessage = stringResource(R.string.backup_choose_again),
            yesEnabled = status.canMutate,
            noEnabled = status.canMutate,
            onYes = {
                step = null
                enableFolder(current.uri)
            },
            onNo = {
                step = null
                pickFolder.launch(null)
            },
            onDismiss = {
                step = null
            }
        )
        BackupStep.ViewPhrase -> ViewPhraseDialog(onClose = { step = null })
        BackupStep.ConfirmTurnOff -> ConfirmDialog(
            title = stringResource(R.string.backup_turn_off),
            message = stringResource(R.string.backup_turn_off_msg),
            yesMessage = stringResource(R.string.backup_turn_off_yes),
            yesEnabled = status.canMutate,
            onYes = {
                step = null
                AutoBackup.disable(ctx)
            },
            onNo = { step = null }
        )
        BackupStep.ConfirmExport -> ConfirmDialog(
            title = stringResource(R.string.backup_export_unencrypted),
            message = stringResource(R.string.backup_export_warning),
            yesMessage = stringResource(R.string.backup_export_yes),
            onYes = {
                step = null
                exportFile.launch("taskapp_backup_" + LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm")) + ".json")
            },
            onNo = { step = null }
        )
        null -> {}
    }

    RestoreDialogs(vm, restoreStep, onStep = { restoreStep = it })
}

/** The Tools menu entry: its title, with the backup's state underneath. */
@Composable
internal fun BackupMenuLabel() {
    val colors = providePanelColors()
    val status by AutoBackup.status(LocalContext.current).collectAsState()
    val settings = status.settings
    Column {
        Text(stringResource(R.string.backup_file_title), color = colors.surfaceText)
        Text(
            text = when {
                status.restartRequired -> stringResource(R.string.backup_restart_required)
                status.saving -> stringResource(R.string.backup_saving)
                status.error != null -> stringResource(R.string.backup_failed_tap_to_fix)
                status.operationError != null -> stringResource(R.string.backup_folder_failed_tap_to_fix)
                settings == null -> stringResource(R.string.backup_off)
                settings.needsMigration -> stringResource(R.string.backup_migration_notice)
                else -> savedText(status)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (status.error != null || status.operationError != null || status.restartRequired || settings?.needsMigration == true)
                colors.brightRed else colors.surfaceText
        )
    }
}

@Composable
internal fun formatBackupTime(instant: Instant): String =
    formatDateTime(instant, LocalConfiguration.current.locales[0], ZoneId.systemDefault())

@Composable
private fun BackupHeader(status: BackupStatus) {
    val colors = providePanelColors()
    val body = MaterialTheme.typography.bodyMedium
    val settings = status.settings
    Column(
        modifier = Modifier.padding(
            horizontal = PanelConstants.HORIZONTAL_PADDING,
            vertical = PanelConstants.SECTION_VERTICAL_PADDING
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(stringResource(R.string.backup_file_title), style = MaterialTheme.typography.titleLarge, color = colors.surfaceText)
        if (settings == null) {
            Text(stringResource(R.string.backup_file_intro), style = body, color = colors.surfaceText)
        } else {
            val location = stringResource(
                if (settings.inPhoneStorage) R.string.backup_phone_storage else R.string.backup_not_phone_storage
            )
            Text(location, style = body, color = colors.surfaceText)
            if (settings.needsMigration) {
                Text(settings.fileName, style = body, color = colors.surfaceText)
            } else {
                Text(stringResource(R.string.backup_current_files), style = body, color = colors.surfaceText)
                settings.slots.forEach { Text(it.fileName, style = body, color = colors.surfaceText) }
                if (settings.savedAt != null) Text(stringResource(R.string.backup_last_saved_file, settings.fileName),
                    style = body, color = colors.surfaceText)
            }
            Text(savedText(status), style = body, color = colors.surfaceText)
            Text(stringResource(if (settings.needsMigration) R.string.backup_migrate else R.string.backup_two_files),
                style = body, color = colors.surfaceText)
        }
        if (status.saving) Text(stringResource(R.string.backup_saving), style = body, color = colors.surfaceText)
        if (status.restartRequired)
            Text(stringResource(R.string.backup_error_settings), style = body, color = colors.brightRed)
        status.error?.let { Text(errorText(it, hasSavedBackup = settings?.savedAt != null), style = body, color = colors.brightRed) }
        if (status.error?.kind == BackupError.Kind.FAILED)
            Text(stringResource(R.string.backup_retry_recovery), style = body, color = colors.brightRed)
        status.operationError?.let {
            Text(stringResource(if (settings == null) R.string.backup_setup_failed else R.string.backup_folder_change_failed),
                style = body, color = colors.brightRed)
            Text(errorText(it, setup = true, hasSavedBackup = settings?.savedAt != null), style = body, color = colors.brightRed)
        }
    }
}

@Composable
private fun savedText(status: BackupStatus): String {
    val savedAt = status.settings?.savedAt
    return when {
        savedAt != null -> stringResource(R.string.backup_saved_at, formatBackupTime(savedAt))
        else -> stringResource(R.string.backup_not_saved_yet)
    }
}

@Composable
private fun errorText(error: BackupError, setup: Boolean = false, hasSavedBackup: Boolean): String = when (error.kind) {
    BackupError.Kind.NO_ACCESS -> stringResource(if (setup) R.string.backup_file_not_usable else R.string.backup_error_no_access)
    BackupError.Kind.PHRASE_CHANGED -> stringResource(R.string.backup_error_phrase)
    BackupError.Kind.TOO_LARGE -> stringResource(R.string.err_backup_too_large) +
        if (hasSavedBackup) " " + stringResource(R.string.backup_error_too_large) else ""
    BackupError.Kind.ACTIVE_DAMAGED -> stringResource(R.string.backup_error_active_damaged)
    BackupError.Kind.SETTINGS_FAILED -> stringResource(R.string.backup_error_settings)
    BackupError.Kind.FAILED -> stringResource(R.string.backup_error_failed, formatBackupTime(error.at)) +
        (error.detail?.let { "\n($it)" } ?: "")
}
