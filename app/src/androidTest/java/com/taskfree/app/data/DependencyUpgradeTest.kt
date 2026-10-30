package com.taskfree.app.data

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskfree.app.Prefs
import com.taskfree.app.data.backup.BackupCrypto
import com.taskfree.app.data.database.AppDatabase
import com.taskfree.app.data.entities.Category
import com.taskfree.app.data.entities.Task
import com.taskfree.app.data.entities.TaskNote
import com.taskfree.app.data.preferences.TipPreferences
import com.taskfree.app.data.repository.BackupManager
import com.taskfree.app.data.repository.BackupSource
import com.taskfree.app.data.repository.TaskRepository
import com.taskfree.app.domain.model.Recurrence
import com.taskfree.app.domain.model.TaskStatus
import com.taskfree.app.settings.SettingsPrefs
import com.taskfree.app.ui.onboarding.TipId
import com.taskfree.app.ui.theme.TextScaleOption
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate

/** Explicit two-APK handoff; intentionally has no ResetAppStateRule. See docs/dependency-update.md. */
@RunWith(AndroidJUnit4::class)
class DependencyUpgradeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    // Public synthetic test phrase only. Never use it for real data.
    private val phrase = listOf("apple", "brick", "cloud", "delta", "eagle", "flame", "grape", "house")
    private val createdAt = Instant.parse("2026-10-29T09:00:00Z")
    private val categories = listOf(Category(1, "Upgrade / Actualización", 0xFF3F51B5))
    private val tasks = listOf(
        Task(1, 1, "Recurring reminder", due = LocalDate.of(2030, 1, 2),
            baseDate = LocalDate.of(2030, 1, 2), singleCategoryPageOrder = 0,
            recurrence = Recurrence.DAILY, reminderTime = Instant.parse("2030-01-02T10:00:00Z"),
            originalCreatedAt = createdAt, occurrenceCreatedAt = createdAt),
        Task(2, 1, "Archived task", singleCategoryPageOrder = 1, allCategoryPageOrder = 1,
            status = TaskStatus.DONE, isArchived = true, completedDate = LocalDate.of(2026, 10, 29))
    )
    private val notes = listOf(TaskNote(1, 1, "First line\nNota: https://example.com",
        createdAt, createdAt.plusSeconds(60)))

    @Test fun dataSurvivesDependencyUpgrade() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val action = args.getString("dependencyUpgradeAction")
        assumeTrue("Run explicitly with seed or verify on a disposable emulator", action != null)
        check(args.getString("disposableEmulator") == "true" &&
            (Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))) {
            "Dependency upgrade handoff requires an explicitly selected disposable emulator"
        }
        val mode = args.getString("dependencyUpgradeMode")
        require(mode == "plain" || mode == "encrypted")
        val directory = File(context.getExternalFilesDir(null), "dependency-upgrade/$mode")
        when (action) {
            "seed" -> seed(directory, mode == "encrypted")
            "verify" -> verify(directory, mode == "encrypted")
            else -> error("Unknown dependencyUpgradeAction: $action")
        }
    }

    private suspend fun seed(directory: File, encrypted: Boolean) {
        RealDatabaseMigrator.resetProcessForTesting()
        RealDatabaseMigrator.resetEncryption(context)
        directory.mkdirs()
        AppDatabaseFactory.buildInternal(context, AppDatabaseFactory.DB_NAME).useDatabase { db ->
            db.categoryDao().insertAll(categories)
            db.taskDao().insertAll(tasks)
            db.taskNoteDao().insertAll(notes)
            val json = BackupManager.buildJson(db)
            File(directory, "baseline.json").writeBytes(json)
            File(directory, "baseline.tfbackup").writeBytes(
                BackupCrypto.encrypt(json, BackupCrypto.deriveKey(phrase)))
        }
        SettingsPrefs.setTextScaleOption(context, TextScaleOption.XL)
        TipPreferences(context).markSeen(TipId.entries.first())
        if (encrypted) {
            RealDatabaseMigrator.resetProcessForTesting()
            RealDatabaseMigrator.migrateToEncrypted(context, phrase)
        }
        RealDatabaseMigrator.resetProcessForTesting()
        context.getDatabasePath(AppDatabaseFactory.DB_NAME)
            .copyTo(File(directory, "baseline.db"), overwrite = true)
        // Ensure asynchronous preference writes are durable before the APK is replaced.
        check(context.getSharedPreferences("com.taskfree.app.settings", Context.MODE_PRIVATE)
            .edit().commit())
    }

    private suspend fun verify(directory: File, encrypted: Boolean) {
        assertTrue("Seed with the baseline APK first", File(directory, "baseline.db").isFile)
        assertEquals(encrypted, Prefs.isEncrypted(context))
        assertEquals(TextScaleOption.XL, SettingsPrefs.getTextScaleOption(context))
        assertTrue(TipPreferences(context).isSeen(TipId.entries.first()))
        RealDatabaseMigrator.resetProcessForTesting()
        RealDatabaseMigrator.start(context).join()
        assertRows(AppDatabaseFactory.getDatabase(context))
        RealDatabaseMigrator.resetProcessForTesting()
        RealDatabaseMigrator.start(context).join()
        assertRows(AppDatabaseFactory.getDatabase(context))

        val restoreName = "dependency-upgrade-restore.db"
        try {
            AppDatabaseFactory.buildInternal(context, restoreName).useDatabase { db ->
                for (name in listOf("baseline.json", "baseline.tfbackup")) {
                    val source = BackupManager.read(context, Uri.fromFile(File(directory, name)))
                    val backup = when (source) {
                        is BackupSource.Plain -> source.backup
                        is BackupSource.Encrypted -> {
                            assertNull(BackupManager.unlock(source, phrase.reversed()))
                            checkNotNull(BackupManager.unlock(source, phrase))
                        }
                    }
                    BackupManager.restore(backup, TaskRepository(db))
                    assertRows(db)
                    db.clearAllTables()
                }
            }
        } finally {
            context.deleteDatabase(restoreName)
        }
        RealDatabaseMigrator.resetProcessForTesting()
    }

    private suspend fun assertRows(db: AppDatabase) {
        assertEquals(categories, db.categoryDao().getAllNow().sortedBy { it.id })
        assertEquals(tasks, db.taskDao().getAllNow().sortedBy { it.id })
        assertEquals(notes, db.taskNoteDao().getAllNow().sortedBy { it.id })
        db.openHelper.readableDatabase.query("PRAGMA user_version").use {
            assertTrue(it.moveToFirst())
            assertEquals(20, it.getInt(0))
        }
        db.openHelper.readableDatabase.query("PRAGMA integrity_check").use {
            assertTrue(it.moveToFirst())
            assertEquals("ok", it.getString(0))
        }
    }
}
