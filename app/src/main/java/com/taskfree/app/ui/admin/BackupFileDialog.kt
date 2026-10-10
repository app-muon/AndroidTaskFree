package com.taskfree.app.ui.admin

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
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
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val BACKUP_FILE_NAME = "TaskFree.tfbackup"

private sealed interface BackupStep {
    /** The database is encrypted but its phrase isn't on this phone; a new phrase wouldn't match it. */
    data object NoPhrase : BackupStep
    /** Confirms the phrase backups use, then picks a file or accepts the phrase. */
    data class ShowPhrase(val thenPickFile: Boolean) : BackupStep
    data class CloudWarning(val uri: Uri) : BackupStep
    data object ViewPhrase : BackupStep
    data object ConfirmTurnOff : BackupStep
    data object ConfirmExport : BackupStep
}

/** The "Backup file" panel and its flows. It stays composed while the panel is closed. */
@Composable
fun BackupFileDialog(vm: ToolsViewModel, show: Boolean, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = providePanelColors()
    val status by AutoBackup.status(ctx).collectAsState()
    var step by remember { mutableStateOf<BackupStep?>(null) }
    var restoreStep by remember { mutableStateOf<RestoreStep?>(null) }

    val pickFile = rememberLauncherForActivityResult(CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            if (uri.isPhoneStorage()) useFile(ctx, uri) else step = BackupStep.CloudWarning(uri)
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
                val bytes = vm.buildBackup()
                checkNotNull(ctx.contentResolver.openOutputStream(uri)).use { it.write(bytes) }
            }.onSuccess {
                Toast.makeText(ctx, R.string.backup_ok, Toast.LENGTH_LONG).show()
            }.onFailure { e ->
                Toast.makeText(ctx, e.message ?: ctx.getString(R.string.backup_save_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    fun phraseUnavailable() = Prefs.isEncrypted(ctx) && Prefs.loadPhrase(ctx) == null
    fun chooseFile() {
        when {
            phraseUnavailable() -> step = BackupStep.NoPhrase
            Prefs.loadPhrase(ctx) == null -> step = BackupStep.ShowPhrase(thenPickFile = true)
            else -> pickFile.launch(BACKUP_FILE_NAME)
        }
    }
    fun reviewPhrase() {
        step = if (phraseUnavailable()) BackupStep.NoPhrase else BackupStep.ShowPhrase(thenPickFile = false)
    }

    if (show) {
        val settings = status.settings
        val actions = buildList {
            if (settings == null) {
                add(ActionItem(label = stringResource(R.string.backup_set_up), icon = Icons.Outlined.Backup,
                    onClick = { chooseFile() }))
            } else {
                when (settings.error?.kind) {
                    BackupError.Kind.NO_ACCESS -> add(ActionItem(label = stringResource(R.string.backup_choose_again),
                        icon = Icons.Outlined.Warning, iconTint = colors.brightRed, onClick = { chooseFile() }))
                    BackupError.Kind.PHRASE_CHANGED -> add(ActionItem(label = stringResource(R.string.backup_review_phrase),
                        icon = Icons.Outlined.Warning, iconTint = colors.brightRed, onClick = { reviewPhrase() }))
                    else -> {}
                }
                // Stays open, so the status above shows the save happen.
                add(ActionItem(label = stringResource(R.string.backup_now), icon = Icons.Outlined.Backup,
                    enabled = !status.saving, dismissOnClick = false, onClick = { AutoBackup.backUpNow(ctx) }))
                add(ActionItem(label = stringResource(R.string.backup_change_file), icon = Icons.Outlined.FolderOpen,
                    onClick = { chooseFile() }))
                add(ActionItem(label = stringResource(R.string.view_recovery_phrase), icon = Icons.Outlined.Lock,
                    onClick = { step = BackupStep.ViewPhrase }))
                add(ActionItem(label = stringResource(R.string.backup_turn_off), icon = Icons.Outlined.CloudOff,
                    onClick = { step = BackupStep.ConfirmTurnOff }))
            }
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
                onConfirmed = {
                    step = null
                    if (current.thenPickFile) pickFile.launch(BACKUP_FILE_NAME) else AutoBackup.usePhrase(ctx)
                },
                onCancel = { step = null }
            )
        }
        is BackupStep.CloudWarning -> ConfirmDialog(
            title = stringResource(R.string.backup_cloud_title),
            message = stringResource(R.string.backup_cloud_msg),
            yesMessage = stringResource(R.string.backup_use_anyway),
            noMessage = stringResource(R.string.backup_choose_again),
            onYes = {
                step = null
                useFile(ctx, current.uri)
            },
            onNo = {
                step = null
                discard(ctx, current.uri)
                pickFile.launch(BACKUP_FILE_NAME)
            },
            onDismiss = {
                step = null
                discard(ctx, current.uri)
            }
        )
        BackupStep.ViewPhrase -> ViewPhraseDialog(onClose = { step = null })
        BackupStep.ConfirmTurnOff -> ConfirmDialog(
            title = stringResource(R.string.backup_turn_off),
            message = stringResource(R.string.backup_turn_off_msg),
            yesMessage = stringResource(R.string.backup_turn_off_yes),
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
                settings == null -> stringResource(R.string.backup_off)
                settings.error != null -> stringResource(R.string.backup_failed_tap_to_fix)
                else -> savedText(status)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (settings?.error != null) colors.brightRed else colors.surfaceText
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
            Text("${settings.fileName} · $location", style = body, color = colors.surfaceText)
            Text(savedText(status), style = body, color = colors.surfaceText)
            settings.error?.let { Text(errorText(it), style = body, color = colors.brightRed) }
        }
    }
}

@Composable
private fun savedText(status: BackupStatus): String {
    val savedAt = status.settings?.savedAt
    return when {
        status.saving -> stringResource(R.string.backup_saving)
        savedAt != null -> stringResource(R.string.backup_saved_at, formatBackupTime(savedAt))
        else -> stringResource(R.string.backup_not_saved_yet)
    }
}

@Composable
private fun errorText(error: BackupError): String = when (error.kind) {
    BackupError.Kind.NO_ACCESS -> stringResource(R.string.backup_error_no_access)
    BackupError.Kind.PHRASE_CHANGED -> stringResource(R.string.backup_error_phrase)
    BackupError.Kind.FAILED -> stringResource(R.string.backup_error_failed, formatBackupTime(error.at)) +
        (error.detail?.let { "\n($it)" } ?: "")
}

/** Keeps access to [uri] across restarts, then starts backing up to it. */
private fun useFile(ctx: Context, uri: Uri) {
    try {
        ctx.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    } catch (e: SecurityException) {
        Toast.makeText(ctx, R.string.backup_file_not_usable, Toast.LENGTH_LONG).show()
        return
    }
    AutoBackup.enable(ctx, uri, displayName(ctx, uri) ?: BACKUP_FILE_NAME)
}

private fun displayName(ctx: Context, uri: Uri): String? = runCatching {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }
}.getOrNull()

/** Removes the empty file the picker created, when the user doesn't use it after all. */
private fun discard(ctx: Context, uri: Uri) {
    runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, uri) }
}
