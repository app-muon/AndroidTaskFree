package com.taskfree.app.ui.admin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.taskfree.app.R
import com.taskfree.app.data.backup.AutoBackup
import com.taskfree.app.data.backup.BackupError
import com.taskfree.app.data.backup.BackupStatus

/** Current recovery guidance shared by both main screens; historical setup errors stay in the panel. */
@Composable
internal fun BackupMigrationBanner(onOpenBackup: () -> Unit) {
    val status by AutoBackup.status(LocalContext.current).collectAsState()
    val message = backupBannerMessage(status) ?: return
    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(message), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onOpenBackup, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.backup_file_title), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

/** Restart and migration come first. Then every saved error, including those that pause saving until the user acts. */
internal fun backupBannerMessage(status: BackupStatus): Int? = when {
    status.restartRequired -> R.string.backup_error_settings
    status.settings?.needsMigration == true -> R.string.backup_migration_notice
    else -> when (status.error?.kind) {
        BackupError.Kind.FAILED -> R.string.backup_retry_notice
        BackupError.Kind.NO_ACCESS -> R.string.backup_paused_no_access
        BackupError.Kind.PHRASE_CHANGED -> R.string.backup_paused_phrase
        BackupError.Kind.ACTIVE_DAMAGED -> R.string.backup_paused_damaged
        BackupError.Kind.TOO_LARGE -> R.string.backup_paused_too_large
        BackupError.Kind.SETTINGS_FAILED -> R.string.backup_error_settings
        null -> null
    }
}
