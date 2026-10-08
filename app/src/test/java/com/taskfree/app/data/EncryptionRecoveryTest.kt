package com.taskfree.app.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskfree.app.Prefs
import com.taskfree.app.data.database.AppDatabase
import com.taskfree.app.enc.DatabaseKeyManager
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class EncryptionRecoveryTest {
    @get:Rule val mainDispatcher = com.taskfree.app.testutil.MainDispatcherRule()
    @get:Rule val temp = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var files: EncryptionRecovery
    private val phrase = listOf("retained", "for", "retry")
    private lateinit var previousHooks: MigrationHooks
    private lateinit var previousRestart: (Context) -> Unit

    private fun plaintext(value: String) = "SQLite format 3\u0000$value"

    @Before fun setUp() {
        previousHooks = RealDatabaseMigrator.hooks
        previousRestart = RealDatabaseMigrator.restart
        val databases = temp.newFolder("databases")
        val noBackup = temp.newFolder("no_backup")
        context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getDatabasePath(name: String) = File(name).takeIf { it.isAbsolute } ?: File(databases, name)
            override fun getNoBackupFilesDir() = noBackup
            override fun getApplicationContext(): Context = this
            override fun openOrCreateDatabase(name: String, mode: Int,
                factory: android.database.sqlite.SQLiteDatabase.CursorFactory?,
                errorHandler: android.database.DatabaseErrorHandler?) =
                baseContext.openOrCreateDatabase(getDatabasePath(name).path, mode, factory, errorHandler)
        }
        runBlocking { RealDatabaseMigrator.resetProcessForTesting() }
        RealDatabaseMigrator.hooks = MigrationHooks()
        Prefs.clearEncryption(context)
        Prefs.savePhrase(context, phrase)
        files = EncryptionRecovery(context)
    }

    @After fun tearDown() {
        try {
            runBlocking { RealDatabaseMigrator.resetProcessForTesting() }
            Prefs.clearEncryption(context)
            context.getSharedPreferences("runtime_state", Context.MODE_PRIVATE).edit().clear().commit()
        } finally {
            RealDatabaseMigrator.hooks = previousHooks
            RealDatabaseMigrator.restart = previousRestart
        }
    }

    @Test fun `restart rolls back at each precommit stage and between renames and stage writes`() {
        val cases = listOf(
            Triple(MigrationStage.PREPARING, false, false),
            Triple(MigrationStage.READY, false, false),
            Triple(MigrationStage.READY, true, false),
            Triple(MigrationStage.ORIGINAL_MOVED, true, false),
            Triple(MigrationStage.ORIGINAL_MOVED, true, true),
            Triple(MigrationStage.INSTALLED, true, true),
            Triple(MigrationStage.ROLLING_BACK, true, true),
            Triple(MigrationStage.ROLLING_BACK, false, false)
        )
        for ((stage, moved, installed) in cases) {
            files.main.delete()
            files.rollback.delete()
            if (moved) files.rollback.writeText(plaintext("all original fields and links"))
            if (!moved) files.main.writeText(plaintext("all original fields and links"))
            if (installed) files.main.writeText("encrypted")
            files.temp.writeText("partial copy")
            files.write(MigrationJournal(stage, true))
            Prefs.setEncrypted(context, true)
            Prefs.saveDerivedKey(context, byteArrayOf(1, 2))
            DatabaseKeyManager.cacheKey(byteArrayOf(1, 2))

            files.recover()
            files.recover()

            assertEquals(stage.name, plaintext("all original fields and links"), files.main.readText())
            assertFalse(files.rollback.exists())
            assertFalse(files.journal.exists())
            assertFalse(files.temp.exists())
            assertFalse(Prefs.isEncrypted(context))
            assertNull(Prefs.loadDerivedKey(context))
            assertNull(DatabaseKeyManager.getCachedKey())
            assertEquals(phrase, Prefs.loadPhrase(context))
        }
    }

    @Test fun `empty installations roll back every stage without leaving a new database`() {
        for (stage in listOf(MigrationStage.PREPARING, MigrationStage.READY,
            MigrationStage.ORIGINAL_MOVED, MigrationStage.INSTALLED, MigrationStage.ROLLING_BACK)) {
            files.main.writeText("new database")
            files.temp.writeText("partial")
            files.write(MigrationJournal(stage, false))
            files.recover()
            files.recover()
            assertFalse(files.main.exists())
            assertFalse(files.journal.exists())
            assertFalse(Prefs.isEncrypted(context))
        }
    }

    @Test fun `committed cleanup failure keeps encrypted database and retries idempotently`() {
        Prefs.requestEncryption(context, phrase)
        Prefs.startEncryptionAttempt(context) { _, editor -> editor.commit() }
        files.main.writeText("encrypted")
        files.rollback.writeText(plaintext("plain"))
        Prefs.setEncrypted(context, true)
        Prefs.saveDerivedKey(context, byteArrayOf(9))
        files.write(MigrationJournal(MigrationStage.COMMITTED, true))
        val failing = EncryptionRecovery(context, object : MigrationHooks() {
            override fun verifyInstalled(context: Context) {}
            override fun delete(file: File) {
                if (file == files.rollback) throw IOException("cleanup")
                super.delete(file)
            }
        })
        assertEquals(RecoveryResult.CLEANUP_WARNING, failing.recover())
        assertFalse(Prefs.attemptStarted(context))
        assertEquals(Prefs.EncryptionOutcome.CLEANUP_WARNING, Prefs.encryptionOutcome(context))
        assertEquals(MigrationStage.COMMITTED, files.read()!!.stage)
        assertTrue(Prefs.isEncrypted(context))
        assertEquals("encrypted", files.main.readText())
        assertEquals(plaintext("plain"), files.rollback.readText())
        EncryptionRecovery(context, object : MigrationHooks() {
            override fun verifyInstalled(context: Context) {}
        }).recover()
        files.recover()
        assertFalse(files.rollback.exists())
        assertFalse(files.journal.exists())
        assertTrue(Prefs.isEncrypted(context))
        assertArrayEquals(byteArrayOf(9), Prefs.loadDerivedKey(context))
    }

    @Test fun `failed rollback rename and persistence can each be retried`() {
        for (failure in listOf("rename", "plaintext_flags", "plaintext_secrets")) {
            files.main.writeText("encrypted")
            files.rollback.writeText(plaintext("original"))
            files.write(MigrationJournal(MigrationStage.INSTALLED, true))
            val failing = EncryptionRecovery(context, object : MigrationHooks() {
                override fun rename(from: File, to: File) {
                    if (failure == "rename") throw IOException("rename")
                    super.rename(from, to)
                }
                override fun commit(name: String, editor: SharedPreferences.Editor): Boolean =
                    name != failure && super.commit(name, editor)
            })
            assertNotNull(runCatching { failing.recover() }.exceptionOrNull())
            assertTrue(files.journal.exists())
            assertEquals(plaintext("original"), if (files.rollback.exists()) files.rollback.readText() else files.main.readText())
            files.recover()
            files.recover()
            assertEquals(plaintext("original"), files.main.readText())
            assertFalse(Prefs.isEncrypted(context))
            assertFalse(files.journal.exists())
        }
    }

    @Test fun `missing rollback and conflicting orphan files block opening without modifying files`() {
        files.main.writeText("unresolved main")
        files.temp.writeText("unresolved temp")
        files.write(MigrationJournal(MigrationStage.INSTALLED, true))
        repeat(2) {
            assertNotNull(runCatching { AppDatabaseFactory.getDatabase(context) }.exceptionOrNull())
            assertEquals(MigrationStage.INSTALLED, files.read()!!.stage)
            assertEquals("unresolved main", files.main.readText())
            assertEquals("unresolved temp", files.temp.readText())
        }
        files.journal.delete()
        files.legacy.writeText("legacy")
        assertEquals(RecoveryResult.CONFLICT, files.recover())
        assertEquals("legacy", files.legacy.readText())
        assertEquals("unresolved main", files.main.readText())
        assertFalse(files.journal.exists())
        files.legacy.delete()
        files.rollback.writeText("orphan rollback")
        assertNotNull(runCatching { files.recover() }.exceptionOrNull())
        assertEquals("orphan rollback", files.rollback.readText())
    }

    @Test fun `legacy backup is inspected and installed only when main is missing`() {
        AppDatabaseFactory.buildInternal(context, files.legacy.path).useDatabase { it.openHelper.writableDatabase }
        assertEquals(RecoveryResult.READY, files.recover())
        assertTrue(files.main.exists())
        assertFalse(files.legacy.exists())
        assertFalse(Prefs.isEncrypted(context))
        AppDatabaseFactory.requirePlaintextHeader(files.main)
    }

    @Test fun `failed legacy install preserves original and can retry`() {
        AppDatabaseFactory.buildInternal(context, files.legacy.path).useDatabase { it.openHelper.writableDatabase }
        val original = files.legacy.readBytes()
        val failing = EncryptionRecovery(context, object : MigrationHooks() {
            override fun rename(from: File, to: File) {
                if (from == files.selected) throw IOException("install")
                super.rename(from, to)
            }
        })
        assertNotNull(runCatching { failing.recover() }.exceptionOrNull())
        assertArrayEquals(original, files.legacy.readBytes())
        assertEquals(RecoveryResult.READY, files.recover())
        assertTrue(files.main.exists())
    }

    @Test fun `checkpointed sidecar cleanup refuses to discard pending writes`() {
        files.main.writeText(plaintext("original"))
        val wal = File(files.main.path + "-wal").apply { writeText("uncheckpointed") }
        assertNotNull(runCatching { files.removeCheckpointedSidecars(files.main) }.exceptionOrNull())
        assertEquals("uncheckpointed", wal.readText())
        assertEquals(plaintext("original"), files.main.readText())
    }

    @Test fun `journal has no secrets and a malformed journal blocks recovery`() {
        files.write(MigrationJournal(MigrationStage.PREPARING, true))
        assertEquals("1\nPREPARING\ntrue", files.journal.readText())
        files.journal.writeText("damaged")
        assertNotNull(runCatching { files.recover() }.exceptionOrNull())
        assertEquals("damaged", files.journal.readText())
        assertFalse(files.main.exists())
    }

    @Test fun `a repeated journal write error preserves the original exception and database`() = runTest {
        files.main.writeText(plaintext("original"))
        val failure = IOException("journal storage unavailable")
        val hooks = object : MigrationHooks() {
            override fun writeJournal(file: File, value: String) { throw failure }
        }
        val thrown = runCatching {
            RealDatabaseMigrator.migrateToEncrypted(context, phrase, hooks) { _, _ ->
                error("Copy must not start without a journal")
            }
        }.exceptionOrNull()
        // Coroutine debug stack recovery can wrap the same IOException.
        assertTrue(thrown === failure || thrown?.cause === failure)
        assertEquals(plaintext("original"), files.main.readText())
        assertTrue(RealDatabaseMigrator.recoveryBlocked.value)
        RealDatabaseMigrator.recover(context)
        assertFalse(RealDatabaseMigrator.recoveryBlocked.value)
    }

    @Test fun `getter fails immediately even while the migration gate is held`() {
        files.main.writeText("main")
        files.legacy.writeText("backup conflict")
        val executor = Executors.newSingleThreadExecutor()
        val attempted = CountDownLatch(1)
        var released = false
        AppDatabaseFactory.gate.acquire()
        try {
            val opening = executor.submit<Boolean> {
                attempted.countDown()
                runCatching { AppDatabaseFactory.getDatabase(context) }.isFailure
            }
            assertTrue(attempted.await(2, TimeUnit.SECONDS))
            assertTrue(opening.get(2, TimeUnit.SECONDS))
            AppDatabaseFactory.gate.release()
            released = true
            assertTrue(opening.get(5, TimeUnit.SECONDS))
        } finally {
            if (!released) AppDatabaseFactory.gate.release()
            executor.shutdownNow()
        }
        assertEquals("main", files.main.readText())
        assertEquals("backup conflict", files.legacy.readText())
    }

    @Test fun `plaintext builders reject damaged and encrypted headers without changing settings`() {
        // A zero-length file is an empty SQLite database, covered separately below.
        for (bytes in listOf("SQLite format 3".toByteArray(), ByteArray(256) { 7 })) {
            files.main.writeBytes(bytes)
            Prefs.saveDerivedKey(context, byteArrayOf(4))
            assertTrue(runCatching { AppDatabaseFactory.buildInternal(context, files.main.path) }.isFailure)
            assertArrayEquals(bytes, files.main.readBytes())
            assertFalse(Prefs.isEncrypted(context))
            assertArrayEquals(byteArrayOf(4), Prefs.loadDerivedKey(context))
        }
        files.main.writeText(plaintext("valid header"))
        AppDatabaseFactory.requirePlaintextHeader(files.main)
    }

    @Test fun `normal database opened guard remains after closing the instance`() = runTest {
        AppDatabaseFactory.gate.acquire()
        try { AppDatabaseFactory.openNormalLocked(context) } finally { AppDatabaseFactory.gate.release() }
        assertNotNull(AppDatabaseFactory.getDatabase(context))
        AppDatabaseFactory.clearInstance()
        val before = files.main.readBytes()
        val failure = runCatching { RealDatabaseMigrator.migrateToEncrypted(context, phrase) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("fresh process"))
        assertArrayEquals(before, files.main.readBytes())
        assertFalse(files.journal.exists())
    }

    @Test fun `interrupted attempt without a journal persists feedback until acknowledgment`() {
        Prefs.requestEncryption(context, phrase)
        Prefs.startEncryptionAttempt(context) { _, editor -> editor.commit() }
        assertEquals(RecoveryResult.ROLLED_BACK, files.recover())
        assertEquals(RecoveryResult.READY, files.recover())
        assertEquals(Prefs.EncryptionOutcome.ROLLED_BACK, Prefs.encryptionOutcome(context))
        assertFalse(Prefs.encryptionPending(context))
        assertFalse(Prefs.attemptStarted(context))
        Prefs.acknowledgeEncryptionOutcome(context)
        assertNull(Prefs.encryptionOutcome(context))
    }

    @Test fun `outcome must be durable before rollback journal is removed`() {
        files.main.writeText(plaintext("original"))
        files.write(MigrationJournal(MigrationStage.PREPARING, true))
        val failing = EncryptionRecovery(context, object : MigrationHooks() {
            override fun commit(name: String, editor: SharedPreferences.Editor) =
                name != "outcome" && super.commit(name, editor)
        })
        assertTrue(runCatching { failing.recover() }.isFailure)
        assertTrue(files.journal.exists())
        files.recover()
        assertEquals(Prefs.EncryptionOutcome.ROLLED_BACK, Prefs.encryptionOutcome(context))
        assertFalse(files.journal.exists())
    }

    @Test fun `startup is shared and a new request does not migrate in the current normal process`() = runBlocking {
        val job = RealDatabaseMigrator.start(context)
        assertSame(job, RealDatabaseMigrator.start(context))
        job.join()
        assertEquals(DatabaseStartup.READY, RealDatabaseMigrator.startup.value)
        var restarts = 0
        RealDatabaseMigrator.restart = { restarts++ }
        RealDatabaseMigrator.requestEncryption(context, phrase)
        assertEquals(1, restarts)
        assertEquals(phrase, Prefs.loadPhrase(context))
        assertTrue(Prefs.encryptionPending(context))
        assertEquals(DatabaseStartup.RESTART, RealDatabaseMigrator.startup.value)
        assertSame(job, RealDatabaseMigrator.start(context))
        // A pending restart clears by itself, so reminders retry instead of giving up.
        assertEquals(ReceiverDatabase.RetryLater, RealDatabaseMigrator.startupDatabase(context))
        assertFalse(files.journal.exists())
        assertFalse(Prefs.isEncrypted(context))
    }

    @Test fun `receivers in migration mode return unavailable without waiting for the gate`() = runBlocking {
        files.main.writeText("damaged")
        Prefs.requestEncryption(context, phrase)
        AppDatabaseFactory.gate.acquire()
        try { assertEquals(ReceiverDatabase.RetryLater, RealDatabaseMigrator.startupDatabase(context)) }
        finally { AppDatabaseFactory.gate.release() }
        // A malformed file prevents this JVM-only test from needing SQLCipher.
        RealDatabaseMigrator.resetProcessForTesting()
    }

    @Test fun `candidate suggestion requires two successful inspections and explicit choice`() {
        val populated = LegacyCandidate(12, 2, null)
        val empty = LegacyCandidate(0, 1, null)
        assertEquals(LegacyChoice.MAIN, LegacyConflict(populated, empty).suggested)
        assertEquals(LegacyChoice.BACKUP, LegacyConflict(empty, populated).suggested)
        assertNull(LegacyConflict(populated, null).suggested)
        assertNull(LegacyConflict(populated, populated).suggested)
        assertNull(LegacyConflict(empty, empty).suggested)
    }

    @Test fun `receiver wait times out without cancelling shared startup`() = runBlocking {
        AppDatabaseFactory.gate.acquire()
        try {
            assertEquals(ReceiverDatabase.RetryLater,
                kotlinx.coroutines.withTimeout(7_000) { RealDatabaseMigrator.startupDatabase(context) })
            assertTrue(RealDatabaseMigrator.start(context).isActive)
        } finally { AppDatabaseFactory.gate.release() }
        RealDatabaseMigrator.start(context).join()
        assertEquals(DatabaseStartup.READY, RealDatabaseMigrator.startup.value)
    }

    @Test fun `explicit recovery retry clears old blocked feedback when recovery succeeds`() = runBlocking {
        files.journal.writeText("invalid")
        RealDatabaseMigrator.start(context).join()
        assertEquals(DatabaseStartup.BLOCKED, RealDatabaseMigrator.startup.value)
        assertEquals(Prefs.EncryptionOutcome.BLOCKED, Prefs.encryptionOutcome(context))
        check(files.journal.delete()) // Repair this test's synthetic invalid journal.
        RealDatabaseMigrator.retryRecovery(context)
        RealDatabaseMigrator.start(context).join()
        assertEquals(DatabaseStartup.READY, RealDatabaseMigrator.startup.value)
        assertNull(Prefs.encryptionOutcome(context))
        assertNull(RealDatabaseMigrator.outcome.value)
    }

    @Test fun automaticRestartGuardSurvivesProcessResetAndAllowsExplicitRestart() = runBlocking {
        var restarts = 0
        RealDatabaseMigrator.restart = { restarts++ }
        val prefs = context.getSharedPreferences("runtime_state", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        suspend fun restartProcess(failWrite: Boolean = false) {
            RealDatabaseMigrator.resetProcessForTesting()
            Prefs.setEncrypted(context, false)
            Prefs.requestEncryption(context, phrase)
            Prefs.setEncrypted(context, true)
            Prefs.saveDerivedKey(context, byteArrayOf(1))
            files.main.writeText("encrypted test fixture")
            RealDatabaseMigrator.hooks = object : MigrationHooks() {
                override fun verifyInstalled(context: Context) {}
                override fun commit(name: String, editor: SharedPreferences.Editor) =
                    !(failWrite && name == "automatic_restart") && super.commit(name, editor)
            }
            RealDatabaseMigrator.onForeground(context)
            RealDatabaseMigrator.start(context).join()
            assertEquals(DatabaseStartup.RESTART, RealDatabaseMigrator.startup.value)
        }
        restartProcess()
        assertEquals(1, restarts)
        restartProcess()
        assertEquals(1, restarts)
        RealDatabaseMigrator.onBackground()
        prefs.edit().putLong("automaticRestart", 0).commit()
        RealDatabaseMigrator.onForeground(context)
        assertEquals(1, restarts) // Suppression remains even if enough time passes.
        RealDatabaseMigrator.requestRestart(context)
        assertEquals(2, restarts)
        prefs.edit().putLong("automaticRestart", System.currentTimeMillis() + 60_000).commit()
        restartProcess()
        assertEquals(2, restarts)
        prefs.edit().clear().commit()
        restartProcess(failWrite = true)
        assertEquals(2, restarts)
        RealDatabaseMigrator.requestRestart(context)
        assertEquals(3, restarts)
    }

    @Test fun `pending encryption never overwrites unfinished legacy cleanup`() = runBlocking {
        AppDatabaseFactory.buildInternal(context, files.main.path).useDatabase { it.openHelper.writableDatabase }
        AppDatabaseFactory.buildInternal(context, files.legacy.path).useDatabase { it.openHelper.writableDatabase }
        Prefs.requestEncryption(context, phrase)
        RealDatabaseMigrator.restart = {}
        val journalWrites = mutableListOf<String>()
        RealDatabaseMigrator.hooks = object : MigrationHooks() {
            override fun writeJournal(file: File, value: String) {
                journalWrites += value
                super.writeJournal(file, value)
            }
            override fun delete(file: File) {
                if (file == files.displaced) throw IOException("cleanup")
                super.delete(file)
            }
        }
        RealDatabaseMigrator.onForeground(context)
        RealDatabaseMigrator.start(context).join()
        assertEquals(DatabaseStartup.CONFLICT, RealDatabaseMigrator.startup.value)

        assertTrue(RealDatabaseMigrator.retryRecovery(context, LegacyChoice.BACKUP))
        RealDatabaseMigrator.start(context).join()

        assertEquals(DatabaseStartup.RESTART, RealDatabaseMigrator.startup.value)
        assertEquals(MigrationStage.LEGACY_COMMITTED, files.read()!!.stage)
        assertTrue(files.displaced.exists())
        assertTrue(journalWrites.none { it.contains("PREPARING") })
        assertEquals(Prefs.EncryptionOutcome.CLEANUP_WARNING, Prefs.encryptionOutcome(context))
        assertFalse(Prefs.encryptionPending(context))

        // The restarted normal process finishes the cleanup instead of blocking.
        RealDatabaseMigrator.resetProcessForTesting()
        RealDatabaseMigrator.hooks = MigrationHooks()
        RealDatabaseMigrator.start(context).join()
        assertEquals(DatabaseStartup.READY, RealDatabaseMigrator.startup.value)
        assertFalse(files.displaced.exists())
        assertFalse(files.journal.exists())
        assertNull(Prefs.encryptionOutcome(context))
    }

    @Test fun `encryption refuses to start over an existing journal`() = runBlocking {
        files.main.writeText(plaintext("original"))
        files.write(MigrationJournal(MigrationStage.LEGACY_COMMITTED, true))
        val failure = runCatching {
            RealDatabaseMigrator.migrateToEncrypted(context, phrase, MigrationHooks(), recoveryAlreadyChecked = true) { _, _ ->
                error("Copy must not start over an unfinished journal")
            }
        }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("Unfinished recovery journal"))
        assertEquals(MigrationStage.LEGACY_COMMITTED, files.read()!!.stage)
        assertEquals(plaintext("original"), files.main.readText())
        assertFalse(Prefs.attemptStarted(context))
    }

    @Test fun `receivers retry while a rolled-back attempt awaits restart`() = runBlocking {
        Prefs.requestEncryption(context, phrase)
        Prefs.startEncryptionAttempt(context) { _, editor -> editor.commit() }
        RealDatabaseMigrator.onForeground(context)
        RealDatabaseMigrator.start(context).join()
        assertEquals(DatabaseStartup.FAILED, RealDatabaseMigrator.startup.value)
        assertEquals(ReceiverDatabase.RetryLater, RealDatabaseMigrator.startupDatabase(context))
    }

    @Test fun `zero-length main opens as an empty database instead of blocking`() = runBlocking {
        files.main.writeBytes(byteArrayOf())
        RealDatabaseMigrator.start(context).join()
        assertEquals(DatabaseStartup.READY, RealDatabaseMigrator.startup.value)
        assertTrue(AppDatabaseFactory.getDatabase(context).taskDao().getAllNow().isEmpty())
        AppDatabaseFactory.requirePlaintextHeader(files.main)
    }

    @Test fun `failed integrity recheck never leaves its handle for a retry`() = runBlocking {
        Prefs.finishEncryptionAttempt(context, Prefs.EncryptionOutcome.BLOCKED)
        val checked = mutableListOf<AppDatabase>()
        var failCheck = true
        RealDatabaseMigrator.hooks = object : MigrationHooks() {
            override fun reopen(db: AppDatabase) {
                checked += db
                if (failCheck) throw IOException("integrity")
                super.reopen(db)
            }
        }
        RealDatabaseMigrator.start(context).join()
        assertEquals(DatabaseStartup.BLOCKED, RealDatabaseMigrator.startup.value)
        assertEquals(Prefs.EncryptionOutcome.BLOCKED, Prefs.encryptionOutcome(context))
        assertFalse(checked.single().isOpen)

        failCheck = false
        assertTrue(RealDatabaseMigrator.retryRecovery(context))
        RealDatabaseMigrator.start(context).join()
        assertEquals(DatabaseStartup.READY, RealDatabaseMigrator.startup.value)
        assertEquals(2, checked.size)
        assertNotSame(checked[0], checked[1])
        assertSame(checked[1], AppDatabaseFactory.getDatabase(context))
        assertNull(Prefs.encryptionOutcome(context))
    }

    @Test fun `unknown stored outcome reads as none and startup continues`() = runBlocking {
        val runtime = context.getSharedPreferences("runtime_state", Context.MODE_PRIVATE)
        runtime.edit().putString("encryptionOutcome", "FROM_A_NEWER_VERSION").commit()
        assertNull(Prefs.encryptionOutcome(context))
        runtime.edit().putInt("encryptionOutcome", 3).commit()
        assertNull(Prefs.encryptionOutcome(context))
        RealDatabaseMigrator.start(context).join()
        assertEquals(DatabaseStartup.READY, RealDatabaseMigrator.startup.value)
    }

    @Test fun `encryption progress stays out of transferable secrets and is cleared on reset`() {
        val secret = context.getSharedPreferences("encryption_secret", Context.MODE_PRIVATE)
        val runtime = context.getSharedPreferences("runtime_state", Context.MODE_PRIVATE)
        runtime.edit().putLong("automaticRestart", 5).commit()
        Prefs.requestEncryption(context, phrase)
        Prefs.startEncryptionAttempt(context) { _, editor -> editor.commit() }
        Prefs.finishEncryptionAttempt(context, Prefs.EncryptionOutcome.BLOCKED)
        Prefs.preserveLegacySettings(context)
        val operational = setOf("pendingEncryption", "encryptionAttempt", "encryptionOutcome",
            "legacySettingsSaved", "legacyEncrypted", "legacyKey", "legacyPhrase", "legacyHash")

        assertTrue(secret.all.keys.none { it in operational })
        assertTrue(runtime.all.keys.containsAll(
            setOf("pendingEncryption", "encryptionAttempt", "encryptionOutcome", "legacySettingsSaved")))
        assertEquals(phrase, Prefs.loadPhrase(context))

        Prefs.clearEncryption(context)
        assertTrue(runtime.all.keys.none { it in operational })
        assertEquals(5L, runtime.getLong("automaticRestart", 0))
    }
}
