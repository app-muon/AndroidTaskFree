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
import java.time.Duration
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class BackupBannerTest {
    private val folder = Uri.parse("content://test/tree/backups")
    private val time = Instant.parse("2026-10-10T12:00:00Z")
    private val slots = listOf("A", "B").map { BackupSlot(Uri.parse("$folder/$it"), "TaskFree-$it.tfbackup") }

    private fun current(kind: Kind? = null, savedAt: Instant? = time) =
        BackupSettings(folder, "hash", slots = slots, savedAt = savedAt, error = kind?.let { BackupError(it, time) })

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
        expected.forEach { (kind, message) ->
            for (savedAt in listOf(time, time.minus(Duration.ofDays(2)), null)) {
                for (saving in listOf(false, true)) {
                    assertEquals(kind.name, message,
                        backupBannerMessage(BackupStatus(current(kind, savedAt), saving = saving), time))
                }
            }
        }
    }

    @Test fun offOrMissingSaveTimeShowsWarning() {
        assertEquals(R.string.backup_warning_off, backupBannerMessage(BackupStatus(null), time))
        assertEquals(R.string.backup_warning_stale, backupBannerMessage(BackupStatus(current(savedAt = null)), time))
    }

    @Test fun recentAndFutureSaveTimesHaveNoBanner() {
        for (savedAt in listOf(time, time.minus(Duration.ofHours(12)), time.plus(Duration.ofDays(1)))) {
            assertNull(backupBannerMessage(BackupStatus(current(savedAt = savedAt)), time))
        }
    }

    @Test fun warningStartsStrictlyAfter24HoursEvenWithoutChanges() {
        val status = BackupStatus(current().copy(pending = false))
        val boundary = time.plus(Duration.ofHours(24))
        assertNull(backupBannerMessage(status, boundary.minusMillis(1)))
        assertNull(backupBannerMessage(status, boundary))
        assertEquals(R.string.backup_warning_stale, backupBannerMessage(status, boundary.plusMillis(1)))
    }

    @Test fun savingAloneDoesNotClearWarnings() {
        assertEquals(R.string.backup_warning_off, backupBannerMessage(BackupStatus(null, saving = true), time))
        for (savedAt in listOf(null, time.minus(Duration.ofDays(2)))) {
            assertEquals(R.string.backup_warning_stale,
                backupBannerMessage(BackupStatus(current(savedAt = savedAt), saving = true), time))
        }
        assertNull(backupBannerMessage(BackupStatus(current(), saving = true), time))
    }

    @Test fun historicalFolderChangeErrorsDoNotOverrideWarningRules() {
        // Setup errors stay in the panel.
        val error = BackupError(Kind.NO_ACCESS, time)
        assertNull(backupBannerMessage(BackupStatus(current(), operationError = error), time))
        assertEquals(R.string.backup_warning_off,
            backupBannerMessage(BackupStatus(null, operationError = error), time))
        assertEquals(R.string.backup_warning_stale,
            backupBannerMessage(BackupStatus(current(savedAt = null), operationError = error), time))
    }

    @Test fun restartThenMigrationTakePriority() {
        val legacy = BackupSettings(folder, "hash", legacyFileName = "Old.tfbackup", error = BackupError(Kind.PHRASE_CHANGED, time))
        assertEquals(R.string.backup_migration_notice, backupBannerMessage(BackupStatus(legacy), time))
        assertEquals(R.string.backup_error_settings, backupBannerMessage(BackupStatus(legacy, restartRequired = true), time))
        assertEquals(R.string.backup_error_settings,
            backupBannerMessage(BackupStatus(current(Kind.ACTIVE_DAMAGED), restartRequired = true), time))
        assertEquals(R.string.backup_error_settings, backupBannerMessage(BackupStatus(null, restartRequired = true), time))
    }
}
