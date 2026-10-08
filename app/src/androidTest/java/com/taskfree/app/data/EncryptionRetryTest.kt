package com.taskfree.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskfree.app.Prefs
import com.taskfree.app.ResetAppStateRule
import com.taskfree.app.data.AppDatabaseFactory.TEMP_DB_NAME
import com.taskfree.app.data.database.AppDatabase
import com.taskfree.app.data.entities.Category
import com.taskfree.app.data.entities.Task
import com.taskfree.app.domain.model.Recurrence
import com.taskfree.app.domain.model.TaskStatus
import com.taskfree.app.enc.DatabaseKeyManager
import com.taskfree.app.ui.enc.fetchWords
import com.taskfree.app.ui.enc.MnemonicManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class EncryptionRetryTest {
    @get:Rule
    val reset = ResetAppStateRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val phrase = fetchWords().take(8)
    private val otherPhrase = fetchWords().takeLast(8)
    private val categories = listOf(
        Category(id = 1, title = "Home", color = 0xFF336699, categoryPageOrder = 4),
        Category(id = 2, title = "Old category", color = 0xFF123456, isDeleted = true)
    )
    private val parent = Task(
        id = 1, categoryId = 1, text = "Water plants", singleCategoryPageOrder = 4,
        allCategoryPageOrder = 8, status = TaskStatus.DONE,
        due = LocalDate.of(2026, 10, 6), baseDate = LocalDate.of(2026, 10, 6),
        completedDate = LocalDate.of(2026, 10, 6), recurrence = Recurrence.DAILY,
        reminderTime = Instant.parse("2026-10-06T08:00:00Z"),
        originalCreatedAt = Instant.parse("2025-07-15T12:00:00.123Z"),
        occurrenceCreatedAt = Instant.parse("2026-10-06T08:00:00.456Z")
    )
    private val tasks = listOf(
        parent,
        parent.copy(
            id = 2, text = "Edited successor", sourceTaskId = 1,
            status = TaskStatus.IN_PROGRESS, completedDate = null,
            due = LocalDate.of(2026, 10, 7), baseDate = LocalDate.of(2026, 10, 7),
            reminderTime = Instant.parse("2026-10-07T08:00:00Z"),
            singleCategoryPageOrder = 7, allCategoryPageOrder = 13,
            occurrenceCreatedAt = Instant.parse("2026-10-06T09:00:00.789Z")
        ),
        Task(id = 3, categoryId = 2, text = "Archived legacy task",
            singleCategoryPageOrder = 9, allCategoryPageOrder = 15, isArchived = true)
    )

    @Test
    fun skipRestoredEncryptedDatabaseRemovesSidecarsAndAllowsStartup() = runBlocking {
        seedSource()
        RealDatabaseMigrator.migrateToEncrypted(context, phrase)
        reset.newProcess()
        Prefs.clearEncryptionSecrets(context) // Restored encrypted database without its local key.
        val files = EncryptionRecovery(context)
        val sidecars = listOf("-wal", "-shm", "-journal").map { File(files.main.path + it) }
        sidecars.forEach { it.writeText("restored sidecar") }
        RealDatabaseMigrator.start(context).join()
        assertEquals(DatabaseStartup.NEEDS_KEY, RealDatabaseMigrator.startup.value)
        var deleted = 0
        RealDatabaseMigrator.hooks = object : MigrationHooks() {
            override fun delete(file: File) {
                super.delete(file)
                if (file in sidecars) {
                    assertFalse(file.exists())
                    deleted++
                }
            }
        }
        RealDatabaseMigrator.skipRestoredDatabase(context)
        RealDatabaseMigrator.start(context).join()
        assertEquals(3, deleted)
        assertEquals(DatabaseStartup.READY, RealDatabaseMigrator.startup.value)
        assertFalse(Prefs.isEncrypted(context))
        assertTrue(AppDatabaseFactory.getDatabase(context).taskDao().getAllNow().isEmpty())
        AppDatabaseFactory.requirePlaintextHeader(files.main)
    }

    @Test
    fun encryptedPlusPendingPreservesDataAndClearsRequestBeforeRestart() = runBlocking {
        seedSource()
        RealDatabaseMigrator.migrateToEncrypted(context, phrase)
        reset.newProcess()
        // Obsolete markers from a previously committed migration.
        context.getSharedPreferences("runtime_state", Context.MODE_PRIVATE).edit()
            .putBoolean("pendingEncryption", true).putBoolean("encryptionAttempt", true)
            .putString("encryptionOutcome", "BLOCKED").commit()
        var restarts = 0
        RealDatabaseMigrator.restart = { restarts++ }
        RealDatabaseMigrator.onForeground(context)
        RealDatabaseMigrator.start(context).join()
        assertEquals(DatabaseStartup.RESTART, RealDatabaseMigrator.startup.value)
        assertEquals(1, restarts)
        assertFalse(Prefs.encryptionPending(context))
        assertFalse(Prefs.attemptStarted(context))
        assertNull(Prefs.encryptionOutcome(context))
        assertStoredContents()
        reset.newProcess()
        RealDatabaseMigrator.onForeground(context)
        RealDatabaseMigrator.start(context).join()
        assertEquals(DatabaseStartup.READY, RealDatabaseMigrator.startup.value)
        assertContents(AppDatabaseFactory.getDatabase(context))
        assertEquals(1, restarts)
        assertFalse(RealDatabaseMigrator.requestEncryption(context, phrase))
        assertFalse(Prefs.encryptionPending(context))
        assertEquals(1, restarts)
    }

    @Test
    fun receiverBeforeActivityWaitsForForegroundAndMigratesOnce() = runBlocking {
        seedSource()
        Prefs.requestEncryption(context, phrase)
        val starts = java.util.concurrent.atomic.AtomicInteger()
        RealDatabaseMigrator.hooks = object : MigrationHooks() {
            override fun writeJournal(file: File, value: String) {
                if (value.contains("PREPARING")) starts.incrementAndGet()
                super.writeJournal(file, value)
            }
        }
        var restarts = 0
        RealDatabaseMigrator.restart = { restarts++ }
        assertEquals(ReceiverDatabase.RetryLater, RealDatabaseMigrator.startupDatabase(context))
        val job = RealDatabaseMigrator.start(context)
        assertTrue(job.isActive)
        assertSame(job, RealDatabaseMigrator.start(context))
        assertEquals(0, starts.get())
        assertFalse(EncryptionRecovery(context).journal.exists())
        assertFalse(Prefs.attemptStarted(context))
        RealDatabaseMigrator.onForeground(context)
        job.join()
        RealDatabaseMigrator.onBackground()
        RealDatabaseMigrator.onForeground(context)
        assertSame(job, RealDatabaseMigrator.start(context))
        assertEquals(1, starts.get())
        assertEquals(1, restarts)
        assertEncryptedContents(phrase)
    }

    @Test
    fun staleTemporaryDatabaseWithSameKeyIsDiscarded() = runBlocking {
        staleDatabaseIsDiscarded(phrase)
    }

    @Test
    fun staleTemporaryDatabaseWithDifferentKeyIsDiscarded() = runBlocking {
        staleDatabaseIsDiscarded(otherPhrase)
    }

    private suspend fun staleDatabaseIsDiscarded(stalePhrase: List<String>) {
        seedSource()
        val stale = AppDatabaseFactory.createTempEncryptedDatabase(
            context, DatabaseKeyManager.deriveKeyFromPhrase(stalePhrase)
        )
        try {
            stale.categoryDao().insertAll(listOf(Category(id = 90, title = "Stale", color = 0)))
            stale.taskDao().insertAll(listOf(parent.copy(id = 90, categoryId = 90)))
        } finally {
            stale.close()
        }
        // Leftover SQLite sidecars must be removed along with the stale database.
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            File(context.getDatabasePath(TEMP_DB_NAME).path + suffix).writeText("stale")
        }

        RealDatabaseMigrator.migrateToEncrypted(context, phrase)

        assertEncryptedContents(phrase)
    }

    @Test
    fun partialCopyFailureCanRetryWithSamePhrase() = runBlocking {
        partialCopyThenRetry(phrase)
    }

    @Test
    fun partialCopyFailureCanRetryWithDifferentPhrase() = runBlocking {
        partialCopyThenRetry(otherPhrase)
    }

    private suspend fun partialCopyThenRetry(retryPhrase: List<String>) {
        seedSource()
        val failure = IOException("Injected failure after a partial copy")
        val thrown = runCatching {
            RealDatabaseMigrator.migrateToEncrypted(context, phrase) { source, target ->
                target.categoryDao().insertAll(source.categoryDao().getAllNow())
                target.taskDao().insertAll(listOf(source.taskDao().getAllNow().first()))
                assertEquals(1, target.taskDao().getAllNow().size)
                throw failure
            }
        }.exceptionOrNull()

        assertSame(failure, thrown)
        assertEquals("Migration failed: ${failure.message}", RealDatabaseMigrator.error.value)
        assertTemporaryFilesRemoved()
        assertFalse(Prefs.isEncrypted(context))
        assertNull(DatabaseKeyManager.getCachedKey())
        // No reset or explicit factory clearing: failure must release its cached plaintext DB.
        assertStoredContents()

        RealDatabaseMigrator.migrateToEncrypted(context, retryPhrase)

        assertEncryptedContents(retryPhrase)
    }

    @Test
    fun cleanupFailurePreservesCopyErrorAndRecoveryCanRetry() = runBlocking {
        seedSource()
        val cleanupFailure = IOException("Injected cleanup failure")
        var copying = false
        val hooks = object : MigrationHooks() {
            override fun delete(file: File) {
                if (copying && file.name == TEMP_DB_NAME) throw cleanupFailure
                super.delete(file)
            }
        }
        val copyFailure = IOException("Injected copy failure")
        val thrown = runCatching {
            RealDatabaseMigrator.migrateToEncrypted(context, phrase, hooks) { source, target ->
                target.categoryDao().insertAll(source.categoryDao().getAllNow())
                copying = true
                throw copyFailure
            }
        }.exceptionOrNull()

        assertSame(copyFailure, thrown)
        assertTrue(copyFailure.suppressed.contains(cleanupFailure))
        assertTrue(RealDatabaseMigrator.recoveryBlocked.value)
        assertFalse(Prefs.isEncrypted(context))
        assertNull(DatabaseKeyManager.getCachedKey())
        RealDatabaseMigrator.recover(context)
        assertFalse(RealDatabaseMigrator.recoveryBlocked.value)
        assertStoredContents()
        RealDatabaseMigrator.migrateToEncrypted(context, otherPhrase)
        assertEncryptedContents(otherPhrase)
    }

    @Test
    fun remainingTemporaryFilesAbortBeforeCopying() = runBlocking {
        seedSource()
        val tempFile = context.getDatabasePath(TEMP_DB_NAME)
        tempFile.writeText("leftover database")
        val hooks = object : MigrationHooks() {
            override fun delete(file: File) {
                if (file == tempFile) error("Cannot remove temporary file")
                super.delete(file)
            }
        }
        var copied = false
        val thrown = runCatching {
            RealDatabaseMigrator.migrateToEncrypted(context, phrase, hooks) { _, _ -> copied = true }
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertFalse(copied)
        assertEquals("leftover database", tempFile.readText())
        assertFalse(Prefs.isEncrypted(context))
        assertNull(DatabaseKeyManager.getCachedKey())
        // Recovery completes before normal access and before any retry.
        RealDatabaseMigrator.recover(context)
        assertStoredContents()
        RealDatabaseMigrator.migrateToEncrypted(context, phrase)
        assertEncryptedContents(phrase)
    }

    @Test
    fun checkpointSwapPersistenceAndReopenFailuresPreserveEveryField() = runBlocking {
        for (failurePoint in listOf("target checkpoint", "source checkpoint", "rollback copy",
            "install", "reopen", "secrets", "flags", "commit journal")) {
            seedSource()
            var checkpoints = 0
            val failure = IOException(failurePoint)
            val hooks = object : MigrationHooks() {
                override fun checkpoint(db: AppDatabase) {
                    checkpoints++
                    if ((failurePoint == "target checkpoint" && checkpoints == 1) ||
                        (failurePoint == "source checkpoint" && checkpoints == 2)) throw failure
                    super.checkpoint(db)
                }
                override fun rename(from: File, to: File) {
                    if ((failurePoint == "rollback copy" && to.name == "encryption-rollback.db") ||
                        (failurePoint == "install" && from.name == TEMP_DB_NAME)) throw failure
                    super.rename(from, to)
                }
                override fun reopen(db: AppDatabase) {
                    if (failurePoint == "reopen") throw failure
                    super.reopen(db)
                }
                override fun commit(name: String, editor: SharedPreferences.Editor): Boolean {
                    if (name == failurePoint) return false
                    return super.commit(name, editor)
                }
                override fun writeJournal(file: File, value: String) {
                    if (failurePoint == "commit journal" && value.contains("\nCOMMITTED\n")) throw failure
                    super.writeJournal(file, value)
                }
            }
            val thrown = runCatching {
                RealDatabaseMigrator.migrateToEncrypted(context, phrase, hooks)
            }.exceptionOrNull()
            assertTrue("No failure at $failurePoint", thrown != null)
            assertFalse(Prefs.isEncrypted(context))
            assertNull(DatabaseKeyManager.getCachedKey())
            assertNull(Prefs.loadDerivedKey(context))
            assertEquals(phrase, Prefs.loadPhrase(context))
            assertStoredContents()
            // Exercise a different phrase after every rollback, clearing cached keys on reopen.
            RealDatabaseMigrator.migrateToEncrypted(context, otherPhrase)
            assertEncryptedContents(otherPhrase)
            AppDatabaseFactory.clearInstance()
            context.deleteDatabase("checklists.db")
            Prefs.clearEncryption(context)
            DatabaseKeyManager.clearCachedKey()
        }
    }

    @Test
    fun verificationRejectsAnIncompleteCopyEvenWithoutAnException() = runBlocking {
        seedSource()
        val failure = runCatching {
            RealDatabaseMigrator.migrateToEncrypted(context, phrase) { source, target ->
                target.categoryDao().insertAll(source.categoryDao().getAllNow())
                target.taskDao().insertAll(source.taskDao().getAllNow().take(1))
            }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertStoredContents()
        assertFalse(Prefs.isEncrypted(context))
    }

    @Test
    fun cancellationRollsBackWithoutLosingItsIdentity() = runBlocking {
        seedSource()
        val cancellation = CancellationException("cancel copy")
        val thrown = runCatching {
            RealDatabaseMigrator.migrateToEncrypted(context, phrase) { source, target ->
                target.categoryDao().insertAll(source.categoryDao().getAllNow())
                throw cancellation
            }
        }.exceptionOrNull()
        assertSame(cancellation, thrown)
        assertStoredContents()
        assertFalse(Prefs.isEncrypted(context))
        assertNull(DatabaseKeyManager.getCachedKey())
    }

    @Test
    fun committedCleanupFailureNeverRevertsEncryption() = runBlocking {
        seedSource()
        val hooks = object : MigrationHooks() {
            override fun delete(file: File) {
                if (file.name == "encryption-rollback.db") throw IOException("cleanup")
                super.delete(file)
            }
        }
        RealDatabaseMigrator.migrateToEncrypted(context, phrase, hooks)
        assertEquals(100, RealDatabaseMigrator.progress.value)
        assertEquals(phrase, Prefs.loadPhrase(context))
        assertTrue(MnemonicManager.isPhraseValid(context, phrase))
        assertTrue(Prefs.isEncrypted(context))
        val files = EncryptionRecovery(context)
        assertEquals(MigrationStage.COMMITTED, files.read()!!.stage)
        assertTrue(files.rollback.exists())
        AppDatabaseFactory.clearInstance()
        DatabaseKeyManager.clearCachedKey()
        reset.newProcess()
        assertContents(reset.db()) // Startup completes committed cleanup.
        assertFalse(files.rollback.exists())
        assertFalse(files.journal.exists())
        assertStoredContents()
    }

    @Test
    fun cancellationAfterPreferencePersistenceStillRollsBack() = runBlocking {
        seedSource()
        val cancellation = CancellationException("cancel before commitment")
        val hooks = object : MigrationHooks() {
            override fun commit(name: String, editor: SharedPreferences.Editor): Boolean {
                val committed = super.commit(name, editor)
                if (name == "flags") throw cancellation
                return committed
            }
        }
        assertSame(cancellation, runCatching {
            RealDatabaseMigrator.migrateToEncrypted(context, phrase, hooks)
        }.exceptionOrNull())
        assertFalse(Prefs.isEncrypted(context))
        assertNull(Prefs.loadDerivedKey(context))
        assertNull(DatabaseKeyManager.getCachedKey())
        assertEquals(phrase, Prefs.loadPhrase(context))
        assertStoredContents()
    }

    @Test
    fun failedRollbackBlocksAccessUntilRecoveryRetry() = runBlocking {
        seedSource()
        val reopenFailure = IOException("reopen")
        val rollbackFailure = IOException("rollback")
        val hooks = object : MigrationHooks() {
            override fun reopen(db: AppDatabase) { throw reopenFailure }
            override fun rename(from: File, to: File) {
                if (from.name == "encryption-rollback.db") throw rollbackFailure
                super.rename(from, to)
            }
        }
        val thrown = runCatching {
            RealDatabaseMigrator.migrateToEncrypted(context, phrase, hooks)
        }.exceptionOrNull()
        assertSame(reopenFailure, thrown)
        assertTrue(reopenFailure.suppressed.contains(rollbackFailure))
        assertTrue(RealDatabaseMigrator.recoveryBlocked.value)
        val files = EncryptionRecovery(context)
        assertTrue(files.rollback.exists())
        assertEquals(MigrationStage.ROLLING_BACK, files.read()!!.stage)
        RealDatabaseMigrator.recover(context)
        assertStoredContents()
        assertFalse(RealDatabaseMigrator.recoveryBlocked.value)
    }

    @Test
    fun emptyInstallationUsesVerifiedEncryptedCommit() = runBlocking {
        RealDatabaseMigrator.migrateToEncrypted(context, phrase)
        assertTrue(context.getDatabasePath("checklists.db").exists())
        assertTrue(Prefs.isEncrypted(context))
        AppDatabaseFactory.clearInstance()
        DatabaseKeyManager.clearCachedKey()
        reset.newProcess()
        assertTrue(reset.db().taskDao().getAllNow().isEmpty())
        assertTrue(reset.db().categoryDao().getAllNow().isEmpty())
        assertFalse(EncryptionRecovery(context).journal.exists())
    }

    @Test
    fun restartRecoversEveryDurableStageWithRealSqlcipherFiles() = runBlocking {
        // An Error simulates process interruption: it bypasses the migration's exception rollback.
        class Interrupted : Error()
        for (stage in listOf(MigrationStage.PREPARING, MigrationStage.READY,
            MigrationStage.ORIGINAL_MOVED, MigrationStage.INSTALLED, MigrationStage.COMMITTED)) {
            seedSource()
            val hooks = object : MigrationHooks() {
                override fun writeJournal(file: File, value: String) {
                    super.writeJournal(file, value)
                    if (value.contains("\n${stage.name}\n")) throw Interrupted()
                }
            }
            assertTrue(runCatching {
                RealDatabaseMigrator.migrateToEncrypted(context, phrase, hooks)
            }.exceptionOrNull() is Interrupted)
            AppDatabaseFactory.clearInstance()
            DatabaseKeyManager.clearCachedKey()
            reset.newProcess()
            assertContents(reset.db())
            assertEquals(stage == MigrationStage.COMMITTED, Prefs.isEncrypted(context))
            val files = EncryptionRecovery(context)
            assertFalse(files.journal.exists())
            assertFalse(files.rollback.exists())
            assertFalse(files.displaced.exists())
            RealDatabaseMigrator.recover(context) // Repeat recovery after the completed restart.
            AppDatabaseFactory.clearInstance()
            context.deleteDatabase("checklists.db")
            Prefs.clearEncryption(context)
            DatabaseKeyManager.clearCachedKey()
        }
    }

    @Test
    fun restartRecoversInterruptionImmediatelyAfterEachSwapRename() = runBlocking {
        class Interrupted : Error()
        for (destination in listOf("encryption-rollback.db", "checklists.db")) {
            seedSource()
            val hooks = object : MigrationHooks() {
                override fun rename(from: File, to: File) {
                    super.rename(from, to)
                    if (to.name == destination) throw Interrupted()
                }
            }
            assertTrue(runCatching {
                RealDatabaseMigrator.migrateToEncrypted(context, phrase, hooks)
            }.exceptionOrNull() is Interrupted)
            DatabaseKeyManager.clearCachedKey()
            reset.newProcess()
            assertContents(reset.db())
            assertFalse(Prefs.isEncrypted(context))
            assertFalse(EncryptionRecovery(context).journal.exists())
        }
    }

    @Test
    fun concurrentOpenFailsImmediatelyAndReceiversCannotOpenAfterCommit() = runBlocking {
        seedSource()
        val copying = CompletableDeferred<Unit>()
        val continueCopy = CompletableDeferred<Unit>()
        val migration = async(Dispatchers.IO) {
            RealDatabaseMigrator.migrateToEncrypted(context, phrase) { source, target ->
                copying.complete(Unit)
                continueCopy.await()
                target.categoryDao().insertAll(source.categoryDao().getAllNow())
                target.taskDao().insertAll(source.taskDao().getAllNow())
            }
        }
        copying.await()
        try {
            assertTrue(runCatching { AppDatabaseFactory.getDatabase(context) }.exceptionOrNull() is IllegalStateException)
        } finally { continueCopy.complete(Unit) }
        migration.await()
        assertTrue(runCatching { AppDatabaseFactory.getDatabase(context) }.isFailure)
        assertEncryptedContents(phrase)
    }

    @Test
    fun committedCleanupWarningAllowsNormalAccessUntilExplicitCleanupRetry() = runBlocking {
        seedSource()
        val cleanupFailure = object : MigrationHooks() {
            override fun delete(file: File) {
                if (file.name == "encryption-rollback.db") throw IOException("retained plaintext")
                super.delete(file)
            }
        }
        RealDatabaseMigrator.migrateToEncrypted(context, phrase, cleanupFailure)
        reset.newProcess()
        RealDatabaseMigrator.hooks = cleanupFailure
        assertContents(reset.db())
        assertEquals(DatabaseStartup.READY, RealDatabaseMigrator.startup.value)
        assertEquals(Prefs.EncryptionOutcome.CLEANUP_WARNING, Prefs.encryptionOutcome(context))
        assertTrue(EncryptionRecovery(context).rollback.exists())
        RealDatabaseMigrator.hooks = MigrationHooks()
        RealDatabaseMigrator.retryRecovery(context)
        RealDatabaseMigrator.start(context).join()
        assertContents(AppDatabaseFactory.getDatabase(context))
        assertFalse(EncryptionRecovery(context).rollback.exists())
    }

    @Test
    fun legacyEncryptedMainIsInspectedDespiteWrongPreferencesAndCanBeSelected() = runBlocking {
        for (choice in LegacyChoice.entries) {
            reset.newProcess()
            RealDatabaseMigrator.resetEncryption(context)
            val key = DatabaseKeyManager.deriveKeyFromPhrase(phrase)
            AppDatabaseFactory.buildInternal(context, AppDatabaseFactory.DB_NAME, key).useDatabase {
                it.categoryDao().insertAll(categories)
                it.taskDao().insertAll(tasks)
            }
            AppDatabaseFactory.buildInternal(context, "checklists_backup.db").useDatabase {
                it.categoryDao().insertAll(listOf(categories.first().copy(title = "Backup")))
            }
            Prefs.savePhrase(context, phrase)
            Prefs.saveDerivedKey(context, DatabaseKeyManager.deriveKeyFromPhrase(otherPhrase))
            Prefs.setEncrypted(context, false)
            val files = EncryptionRecovery(context)
            val originalMain = files.main.readBytes()
            val originalBackup = files.legacy.readBytes()
            val preview = files.inspectLegacy()
            assertEquals(tasks.size, preview.main!!.tasks)
            assertEquals(0, preview.backup!!.tasks)
            assertEquals(LegacyChoice.MAIN, preview.suggested)
            assertArrayEquals(originalMain, files.main.readBytes())
            assertArrayEquals(originalBackup, files.legacy.readBytes())
            assertEquals(RecoveryResult.READY, files.selectLegacy(choice))
            reset.newProcess()
            val reopened = reset.db()
            assertEquals(choice == LegacyChoice.MAIN, Prefs.isEncrypted(context))
            if (choice == LegacyChoice.MAIN) {
                assertArrayEquals(key, Prefs.loadDerivedKey(context))
                assertContents(reopened)
            } else {
                assertTrue(reopened.taskDao().getAllNow().isEmpty())
                assertEquals("Backup", reopened.categoryDao().getAllNow().single().title)
                assertNull(Prefs.loadDerivedKey(context))
            }
            assertFalse(files.legacy.exists())
            assertFalse(files.displaced.exists())
        }
    }

    @Test
    fun unavailableEncryptedLegacyCopyIsNeverLabelledEmpty() = runBlocking {
        seedSource()
        AppDatabaseFactory.buildInternal(context, "checklists_backup.db",
            DatabaseKeyManager.deriveKeyFromPhrase(otherPhrase)).useDatabase {
            it.categoryDao().insertAll(categories)
            it.taskDao().insertAll(tasks)
        }
        Prefs.savePhrase(context, phrase)
        val files = EncryptionRecovery(context)
        val preview = files.inspectLegacy()
        assertEquals(tasks.size, preview.main!!.tasks)
        assertNull(preview.backup)
        assertNull(preview.suggested)
        assertTrue(runCatching { files.selectLegacy(LegacyChoice.BACKUP) }.isFailure)
        assertTrue(files.main.exists())
        assertTrue(files.legacy.exists())
    }

    @Test
    fun legacySelectionWithStoredKeyDoesNotAdvertiseAnUnrelatedPhrase() = runBlocking {
        val key = DatabaseKeyManager.deriveKeyFromPhrase(phrase)
        AppDatabaseFactory.buildInternal(context, AppDatabaseFactory.DB_NAME, key).useDatabase {
            it.categoryDao().insertAll(categories)
            it.taskDao().insertAll(tasks)
        }
        AppDatabaseFactory.buildInternal(context, "checklists_backup.db").useDatabase {
            it.openHelper.writableDatabase
        }
        Prefs.saveDerivedKey(context, key)
        Prefs.savePhrase(context, otherPhrase)
        val failure = EncryptionRecovery(context, object : MigrationHooks() {
            override fun writeJournal(file: File, value: String) {
                if (value.contains("\nLEGACY_COMMITTED\n")) throw IOException("commit")
                super.writeJournal(file, value)
            }
        })
        assertTrue(runCatching { failure.selectLegacy(LegacyChoice.MAIN) }.isFailure)
        assertEquals(otherPhrase, Prefs.loadPhrase(context))
        assertArrayEquals(key, Prefs.loadDerivedKey(context))
        val files = EncryptionRecovery(context)
        assertTrue(files.main.exists())
        assertTrue(files.legacy.exists())
        files.selectLegacy(LegacyChoice.MAIN)
        assertNull(Prefs.loadPhrase(context))
        assertNull(Prefs.loadPhraseHash(context))
        assertArrayEquals(key, Prefs.loadDerivedKey(context))
        assertStoredContents()
    }

    @Test
    fun legacySelectionInterruptionPreservesBothOriginalsBeforeCommit() = runBlocking {
        class Interrupted : Error()
        for (choice in LegacyChoice.entries) {
            for (stage in listOf(MigrationStage.LEGACY_SELECTING, MigrationStage.LEGACY_INSTALLING)) {
                reset.newProcess()
                RealDatabaseMigrator.resetEncryption(context)
                seedSource()
                AppDatabaseFactory.buildInternal(context, "checklists_backup.db").useDatabase {
                    it.categoryDao().insertAll(listOf(categories.first().copy(title = "Backup")))
                }
                val files = EncryptionRecovery(context)
                val originalMain = files.main.readBytes()
                val originalBackup = files.legacy.readBytes()
                val interrupted = EncryptionRecovery(context, object : MigrationHooks() {
                    override fun writeJournal(file: File, value: String) {
                        super.writeJournal(file, value)
                        if (value.contains("\n${stage.name}\n")) throw Interrupted()
                    }
                })
                assertTrue(runCatching { interrupted.selectLegacy(choice) }.exceptionOrNull() is Interrupted)
                assertEquals(RecoveryResult.CONFLICT, files.recover())
                assertArrayEquals(originalMain, files.main.readBytes())
                assertArrayEquals(originalBackup, files.legacy.readBytes())
                assertEquals(RecoveryResult.READY, files.selectLegacy(choice))
            }
        }
    }

    private fun seedSource() {
        reset.newProcess()
        reset.seed {
            categoryDao().insertAll(categories)
            taskDao().insertAll(tasks)
        }
    }

    private suspend fun assertStoredContents() {
        AppDatabaseFactory.buildInternal(context, AppDatabaseFactory.DB_NAME,
            if (Prefs.isEncrypted(context)) Prefs.loadDerivedKey(context) else null).useDatabase { assertContents(it) }
    }

    private suspend fun assertContents(db: AppDatabase) {
        assertEquals(categories, db.categoryDao().getAllNow().sortedBy { it.id })
        assertEquals(tasks, db.taskDao().getAllNow().sortedBy { it.id })
    }

    private suspend fun assertEncryptedContents(expectedPhrase: List<String>) {
        assertTemporaryFilesRemoved()
        assertTrue(Prefs.isEncrypted(context))
        assertNull(RealDatabaseMigrator.error.value)
        assertEquals(100, RealDatabaseMigrator.progress.value)
        assertArrayEquals(
            DatabaseKeyManager.deriveKeyFromPhrase(expectedPhrase),
            DatabaseKeyManager.loadDerivedKey(context)
        )
        AppDatabaseFactory.clearInstance()
        DatabaseKeyManager.clearCachedKey()
        reset.newProcess()
        assertContents(reset.db())
    }

    private fun assertTemporaryFilesRemoved() {
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            val file = File(context.getDatabasePath(TEMP_DB_NAME).path + suffix)
            assertFalse("Temporary file remains: ${file.name}", file.exists())
        }
    }
}
