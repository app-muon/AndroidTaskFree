package com.taskfree.app.ui.admin

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.taskfree.app.R
import com.taskfree.app.data.backup.AutoBackup
import com.taskfree.app.data.backup.BackupError
import com.taskfree.app.data.backup.BackupStatus
import com.taskfree.app.util.AppDateProvider
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

/** Current recovery guidance shared by both main screens; historical setup errors stay in the panel. */
@Composable
internal fun BackupMigrationBanner(onOpenBackup: () -> Unit) {
    val status by AutoBackup.status(LocalContext.current).collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    var now by remember(status) { mutableStateOf(AppDateProvider.current.nowInstant()) }
    // Observe even when hidden so a recent backup can become stale without another UI change.
    LaunchedEffect(lifecycleOwner, status) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                now = AppDateProvider.current.nowInstant()
                delay(60_000)
            }
        }
    }
    val message = backupBannerMessage(status, now) ?: return
    if (message == R.string.backup_warning_off || message == R.string.backup_warning_stale) {
        BackupWarningBanner(message, onOpenBackup)
        return
    }
    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(message), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onOpenBackup, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.backup_file_title), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BackupWarningBanner(message: Int, onOpenBackup: () -> Unit) {
    val dark = isSystemInDarkTheme()
    Surface(
        onClick = onOpenBackup,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        color = if (dark) Color(0xFF4D3800) else Color(0xFFFFF3CD),
        contentColor = if (dark) Color(0xFFFFE8A3) else Color(0xFF664D03)
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, modifier = Modifier.size(20.dp))
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(message), style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.backup_file_title), style = MaterialTheme.typography.labelLarge)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                        modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** Restart, migration and saved errors take priority over warnings, even while saving. */
internal fun backupBannerMessage(status: BackupStatus, now: Instant): Int? = when {
    status.restartRequired -> R.string.backup_error_settings
    status.settings?.needsMigration == true -> R.string.backup_migration_notice
    else -> when (status.error?.kind) {
        BackupError.Kind.FAILED -> R.string.backup_retry_notice
        BackupError.Kind.NO_ACCESS -> R.string.backup_paused_no_access
        BackupError.Kind.PHRASE_CHANGED -> R.string.backup_paused_phrase
        BackupError.Kind.ACTIVE_DAMAGED -> R.string.backup_paused_damaged
        BackupError.Kind.TOO_LARGE -> R.string.backup_paused_too_large
        BackupError.Kind.SETTINGS_FAILED -> R.string.backup_error_settings
        null -> when {
            status.settings == null -> R.string.backup_warning_off
            status.settings.savedAt == null -> R.string.backup_warning_stale
            Duration.between(status.settings.savedAt, now) > Duration.ofHours(24) -> R.string.backup_warning_stale
            else -> null
        }
    }
}
