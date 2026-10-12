package com.taskfree.app.ui.admin

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskfree.app.R
import com.taskfree.app.data.backup.BackupError
import com.taskfree.app.data.backup.BackupError.Kind
import com.taskfree.app.data.backup.BackupSettings
import com.taskfree.app.data.backup.BackupSlot
import com.taskfree.app.data.backup.BackupStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class BackupBannerTest {
    private val folder = Uri.parse("content://test/tree/backups")
    private val time = Instant.parse("2026-10-10T12:00:00Z")
    private val slots = listOf("A", "B").map { BackupSlot(Uri.parse("$folder/$it"), "TaskFree-$it.tfbackup") }

    private fun current(kind: Kind? = null) =
        BackupSettings(folder, "hash", slots = slots, error = kind?.let { BackupError(it, time) })

    @Test fun everySavedErrorShowsBanner() {
        val expected = mapOf(
            Kind.FAILED to R.string.backup_retry_notice,
            Kind.NO_ACCESS to R.string.backup_paused_no_access,
            Kind.PHRASE_CHANGED to R.string.backup_paused_phrase,
            Kind.ACTIVE_DAMAGED to R.string.backup_paused_damaged,
            Kind.TOO_LARGE to R.string.backup_paused_too_large,
            Kind.SETTINGS_FAILED to R.string.backup_error_settings
        )
        assertEquals(Kind.entries.toSet(), expected.keys)
        expected.forEach { (kind, message) -> assertEquals(kind.name, message, backupBannerMessage(BackupStatus(current(kind)))) }
    }

    @Test fun noBannerWhenOffHealthyOrOnlyFolderChangeFailed() {
        assertNull(backupBannerMessage(BackupStatus(null)))
        assertNull(backupBannerMessage(BackupStatus(current())))
        // Setup errors stay in the panel.
        assertNull(backupBannerMessage(BackupStatus(current(), operationError = BackupError(Kind.NO_ACCESS, time))))
        assertNull(backupBannerMessage(BackupStatus(null, operationError = BackupError(Kind.PHRASE_CHANGED, time))))
    }

    @Test fun restartThenMigrationTakePriority() {
        val legacy = BackupSettings(folder, "hash", legacyFileName = "Old.tfbackup", error = BackupError(Kind.PHRASE_CHANGED, time))
        assertEquals(R.string.backup_migration_notice, backupBannerMessage(BackupStatus(legacy)))
        assertEquals(R.string.backup_error_settings, backupBannerMessage(BackupStatus(legacy, restartRequired = true)))
        assertEquals(R.string.backup_error_settings,
            backupBannerMessage(BackupStatus(current(Kind.ACTIVE_DAMAGED), restartRequired = true)))
    }
}
