package com.taskfree.app.data

import android.content.Context
import android.util.Log
import com.taskfree.app.Prefs
import com.taskfree.app.data.database.AppDatabase
import com.taskfree.app.data.entities.Category
import com.taskfree.app.data.entities.Task
import com.taskfree.app.enc.DatabaseKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import com.taskfree.app.util.restartApp
import com.taskfree.app.notifications.NotificationScheduler
import com.taskfree.app.notifications.ReminderAccessNotice
import java.time.Instant

internal enum class DatabaseStartup { CHECKING, READY, NEEDS_KEY, MIGRATING, FAILED, BLOCKED, CONFLICT, RESTART }

internal sealed interface ReceiverDatabase {
    data class Ready(val database: AppDatabase) : ReceiverDatabase
    data object RetryLater : ReceiverDatabase
    data object NeedsUserAction : ReceiverDatabase
}

internal enum class ReminderRestore { RESTORED, RETRY_LATER, NEEDS_USER_ACTION }

/** Only these states need the user; every other non-ready state clears by itself or after a restart. */
private val USER_ACTION_STATES =
    setOf(DatabaseStartup.NEEDS_KEY, DatabaseStartup.BLOCKED, DatabaseStartup.CONFLICT)

private class MissingDatabaseKey : IllegalStateException("Encryption key requires recovery")

object RealDatabaseMigrator {
    // Publish startup transitions on Main; all file/database work below runs on IO.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var startupJob: Job? = null
    @Volatile private var migrationMode: Boolean? = null
    private val _startup = MutableStateFlow(DatabaseStartup.CHECKING)
    internal val startup: StateFlow<DatabaseStartup> = _startup
    private val _outcome = MutableStateFlow<Prefs.EncryptionOutcome?>(null)
    internal val outcome: StateFlow<Prefs.EncryptionOutcome?> = _outcome
    private val _conflict = MutableStateFlow<LegacyConflict?>(null)
    internal val conflict: StateFlow<LegacyConflict?> = _conflict
    internal var restart: (Context) -> Unit = { it.restartApp() }
    internal var hooks: MigrationHooks = MigrationHooks()
    private var autoRestartAttempted = false
    private val foreground = MutableStateFlow(false)
    private val _actionFailed = MutableStateFlow(false)
    internal val actionFailed: StateFlow<Boolean> = _actionFailed
    private val remindersRestored = MutableStateFlow(false)

    /** Captures the mode before any asynchronous startup or normal database open. */
    @Synchronized
    internal fun start(context: Context): Job {
        startupJob?.let { return it }
        migrationMode = migrationMode ?: Prefs.encryptionPending(context)
        if (migrationMode == true) AppDatabaseFactory.disableAccess()
        return launchStartup(context.applicationContext).also { startupJob = it; it.start() }
    }

    private fun launchStartup(context: Context, choice: LegacyChoice? = null): Job = scope.launch(start = CoroutineStart.LAZY) {
        _startup.value = DatabaseStartup.CHECKING
        try {
            // Receivers may capture the mode, but only an activity can release this wait.
            // Once released, this process-owned job survives rotation and backgrounding.
            if (migrationMode == true) foreground.first { it }
            val result = withContext(Dispatchers.IO) {
                AppDatabaseFactory.exclusive {
                    if (choice != null) {
                        val selected = EncryptionRecovery(context, hooks).selectLegacy(choice)
                        _conflict.value = null
                        _recoveryBlocked.value = false
                        selected
                    } else recoverLocked(context, hooks)
                }
            }
            _outcome.value = Prefs.encryptionOutcome(context)
            if (result == RecoveryResult.CONFLICT) {
                _startup.value = DatabaseStartup.CONFLICT
                foreground.first { it }
                _conflict.value = withContext(Dispatchers.IO) {
                    AppDatabaseFactory.exclusive { EncryptionRecovery(context, hooks).inspectLegacy() }
                }
            } else if (migrationMode == true) {
                when {
                    Prefs.isEncrypted(context) -> {
                        withContext(Dispatchers.IO) {
                            AppDatabaseFactory.exclusive {
                                hooks.verifyInstalled(context)
                                val feedback = Prefs.encryptionOutcome(context)
                                    .takeUnless { it == Prefs.EncryptionOutcome.BLOCKED }
                                Prefs.finishEncryptionAttempt(context, feedback, hooks::commit)
                                _outcome.value = feedback
                            }
                        }
                        _startup.value = DatabaseStartup.RESTART
                    }
                    result == RecoveryResult.ROLLED_BACK || _outcome.value == Prefs.EncryptionOutcome.ROLLED_BACK ->
                        _startup.value = DatabaseStartup.FAILED
                    // Unfinished legacy cleanup keeps its journal; a normal process retries it.
                    // The pending request was cleared with the warning, so encryption is re-requested later.
                    result == RecoveryResult.CLEANUP_WARNING -> _startup.value = DatabaseStartup.RESTART
                    else -> {
                        _startup.value = DatabaseStartup.MIGRATING
                        migrateToEncrypted(context, requireNotNull(Prefs.loadPhrase(context)), hooks,
                            recoveryAlreadyChecked = true)
                        _startup.value = DatabaseStartup.RESTART
                    }
                }
            } else {
                openNormal(context)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: MissingDatabaseKey) {
            AppDatabaseFactory.disableAccess()
            _startup.value = DatabaseStartup.NEEDS_KEY
        } catch (e: Exception) {
            Log.e("RealDatabaseMigrator", "Database startup failed", e)
            AppDatabaseFactory.disableAccess()
            _outcome.value = Prefs.encryptionOutcome(context)
            _startup.value = if (_outcome.value == Prefs.EncryptionOutcome.ROLLED_BACK && !_recoveryBlocked.value)
                DatabaseStartup.FAILED else DatabaseStartup.BLOCKED
        }
        if (foreground.value) onForeground(context)
    }

    private suspend fun openNormal(context: Context) {
        check(migrationMode != true) { "Migration processes must restart before normal access" }
        if (Prefs.isEncrypted(context) && DatabaseKeyManager.getCachedKey() == null &&
            Prefs.loadDerivedKey(context) == null) {
            _startup.value = DatabaseStartup.NEEDS_KEY
            return
        }
        val database = withContext(Dispatchers.IO) {
            AppDatabaseFactory.exclusive {
                AppDatabaseFactory.allowNormalAccessLocked()
                AppDatabaseFactory.openNormalLocked(context).also {
                    if (Prefs.encryptionOutcome(context) == Prefs.EncryptionOutcome.BLOCKED) {
                        try {
                            hooks.reopen(it)
                        } catch (e: Exception) {
                            // A handle that failed verification must never be reused by a retry.
                            try { AppDatabaseFactory.closeInternal() } catch (close: Exception) {
                                if (close !== e) e.addSuppressed(close)
                            }
                            throw e
                        }
                        Prefs.acknowledgeEncryptionOutcome(context, hooks::commit)
                        _outcome.value = null
                    }
                }
            }
        }
        _startup.value = DatabaseStartup.READY
        // Only future reminders are restored. Expired reminders are never replayed here.
        try {
            withContext(Dispatchers.IO) {
                database.taskDao().upcomingReminders(Instant.now()).forEach {
                    NotificationScheduler.scheduleSilently(context, it.id, it.reminderTime)
                }
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { Log.e("RealDatabaseMigrator", "Reminder rescheduling failed", e) }
        // Also set after a logged failure, so boot handling never waits on an error.
        remindersRestored.value = true
    }

    @Synchronized
    internal fun retryRecovery(context: Context, choice: LegacyChoice? = null): Boolean {
        if (startupJob?.isActive == true) return false
        // Feedback is cleared only after verified recovery, so failed cleanup keeps its warning.
        _actionFailed.value = false
        launchStartup(context.applicationContext, choice).also { startupJob = it; it.start() }
        return true
    }

    @Synchronized
    internal fun skipRestoredDatabase(context: Context) {
        if (startupJob?.isActive == true) return
        scope.launch(start = CoroutineStart.LAZY) {
            _startup.value = DatabaseStartup.CHECKING
            try {
                withContext(Dispatchers.IO) { resetEncryption(context.applicationContext) }
                _outcome.value = null
                _actionFailed.value = false
                if (migrationMode == true) _startup.value = DatabaseStartup.RESTART
                else openNormal(context.applicationContext)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.e("RealDatabaseMigrator", "Could not reset restored database", e)
                _actionFailed.value = true
                _startup.value = DatabaseStartup.NEEDS_KEY
            }
            if (foreground.value) onForeground(context)
        }.also { startupJob = it; it.start() }
    }

    internal fun requestEncryption(context: Context, phrase: List<String>): Boolean = action {
        Prefs.requestEncryption(context, phrase, hooks::commit)
        AppDatabaseFactory.disableAccess()
        _outcome.value = null
        requestRestart(context)
    }

    internal fun acknowledgeOutcome(context: Context): Boolean = action {
        Prefs.acknowledgeEncryptionOutcome(context, hooks::commit)
        _outcome.value = null
    }

    private inline fun action(block: () -> Unit): Boolean = try {
        block()
        _actionFailed.value = false
        true
    } catch (e: Exception) {
        Log.e("RealDatabaseMigrator", "Encryption action could not be saved", e)
        _actionFailed.value = true
        false
    }

    internal fun onForeground(context: Context) {
        foreground.value = true
        start(context)
        if (_startup.value == DatabaseStartup.READY) ReminderAccessNotice.clear(context)
        if (_startup.value == DatabaseStartup.RESTART && !autoRestartAttempted) {
            // Suppression is sticky for this process, including Home/resume.
            autoRestartAttempted = true
            val allowed = runCatching {
                Prefs.recordAutomaticRestart(context, System.currentTimeMillis(), hooks::commit)
            }.getOrDefault(false)
            if (allowed) requestRestart(context)
        }
    }

    internal fun onBackground() { foreground.value = false }

    internal fun requestRestart(context: Context) {
        autoRestartAttempted = true
        AppDatabaseFactory.disableAccess()
        _startup.value = DatabaseStartup.RESTART
        try { restart(context.applicationContext) } catch (e: Exception) {
            Log.e("RealDatabaseMigrator", "Restart failed; awaiting retry", e)
        }
    }

    /** Receivers may wait briefly for startup, but never block a migration process. */
    internal suspend fun startupDatabase(context: Context): ReceiverDatabase {
        start(context)
        if (migrationMode != true && _startup.value == DatabaseStartup.CHECKING) {
            withTimeoutOrNull(5_000) { startup.first { it != DatabaseStartup.CHECKING } }
        }
        return when (_startup.value) {
            DatabaseStartup.READY -> runCatching {
                ReceiverDatabase.Ready(AppDatabaseFactory.getDatabase(context))
            }.getOrDefault(ReceiverDatabase.RetryLater)
            in USER_ACTION_STATES -> ReceiverDatabase.NeedsUserAction
            // Checking, migrating, a pending restart and a rolled-back attempt use bounded retries.
            else -> ReceiverDatabase.RetryLater
        }
    }

    /** Boot handling must not finish until future reminders have been scheduled again. */
    internal suspend fun awaitReminderRestore(context: Context): ReminderRestore {
        start(context)
        // A migration process cannot open the normal database before restarting, so never wait there.
        // Elsewhere stay under the broadcast deadline; a timeout becomes a follow-up attempt.
        if (migrationMode != true) withTimeoutOrNull(8_000) {
            combine(_startup, remindersRestored) { state, restored ->
                restored || state in USER_ACTION_STATES
            }.first { it }
        }
        return when {
            remindersRestored.value -> ReminderRestore.RESTORED
            _startup.value in USER_ACTION_STATES -> ReminderRestore.NEEDS_USER_ACTION
            else -> ReminderRestore.RETRY_LATER
        }
    }

    internal suspend fun resetProcessForTesting() {
        startupJob?.cancel()
        startupJob?.join()
        startupJob = null
        migrationMode = null
        _startup.value = DatabaseStartup.CHECKING
        _outcome.value = null
        _conflict.value = null
        _error.value = null
        _progress.value = 0
        _recoveryBlocked.value = false
        autoRestartAttempted = false
        foreground.value = false
        _actionFailed.value = false
        remindersRestored.value = false
        AppDatabaseFactory.resetProcessForTesting()
        DatabaseKeyManager.clearCachedKey()
    }

    internal fun resetEncryption(context: Context) {
        AppDatabaseFactory.disableAccess()
        AppDatabaseFactory.exclusive {
            AppDatabaseFactory.closeInternal()
            EncryptionRecovery(context, hooks).resetFiles()
            Prefs.clearEncryption(context)
            DatabaseKeyManager.clearCachedKey()
        }
    }
    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _recoveryBlocked = MutableStateFlow(false)
    val recoveryBlocked: StateFlow<Boolean> = _recoveryBlocked

    internal suspend fun migrateToEncrypted(context: Context, phrase: List<String>) =
        migrateToEncrypted(context, phrase, MigrationHooks(), copyTables = ::copyTables)

    internal suspend fun migrateToEncrypted(
        context: Context, phrase: List<String>,
        copyTables: suspend (AppDatabase, AppDatabase) -> Unit
    ) = migrateToEncrypted(context, phrase, MigrationHooks(), copyTables = copyTables)

    internal suspend fun migrateToEncrypted(
        context: Context, phrase: List<String>, hooks: MigrationHooks,
        recoveryAlreadyChecked: Boolean = false,
        copyTables: suspend (AppDatabase, AppDatabase) -> Unit = ::copyTables
    ) = withContext(Dispatchers.IO) { AppDatabaseFactory.exclusive {
        try {
            currentCoroutineContext().ensureActive()
            AppDatabaseFactory.beginMigrationLocked()
            synchronized(this@RealDatabaseMigrator) { if (migrationMode == null) migrationMode = true }
            _error.value = null
            _progress.value = 0
            val files = EncryptionRecovery(context, hooks)
            // Recovery errors must not run cleanup against unresolved files.
            if (!recoveryAlreadyChecked) {
                check(recoverLocked(context, hooks) != RecoveryResult.CONFLICT) { "Legacy database choice is required" }
            }
            check(!Prefs.isEncrypted(context)) { "Database is already encrypted" }
            // Outside the rollback scope below: an unfinished journal must never be overwritten or rolled back here.
            check(files.read() == null) { "Unfinished recovery journal; refusing to start encryption" }
            AppDatabaseFactory.requirePlaintextHeader(files.main)
            AppDatabaseFactory.closeInternal()
            var state = MigrationJournal(MigrationStage.PREPARING, files.main.exists())
            try {
                Prefs.startEncryptionAttempt(context, hooks::commit)
                files.write(state)
                Prefs.commitMigrationPhrase(context, phrase, hooks::commit)
                val key = DatabaseKeyManager.deriveKeyFromPhrase(phrase)
                _progress.value = 10
                files.deleteDatabaseFiles(files.temp)
                val expected = AppDatabaseFactory.buildInternal(context, AppDatabaseFactory.DB_NAME).useDatabase { source ->
                    MigrationHooks().reopen(source)
                    val original = snapshot(source)
                    AppDatabaseFactory.createTempEncryptedDatabase(context, key).useDatabase { target ->
                        copyTables(source, target)
                        check(original == snapshot(target)) {
                            "Encrypted copy does not match the original database"
                        }
                        hooks.checkpoint(target)
                    }
                    hooks.checkpoint(source)
                    original
                }
                // Both handles are closed; never discard WAL data if checkpointing failed.
                files.removeCheckpointedSidecars(files.main)
                files.removeCheckpointedSidecars(files.temp)
                _progress.value = 60
                currentCoroutineContext().ensureActive()
                state = state.copy(stage = MigrationStage.READY)
                files.write(state)
                if (state.hadOriginal) hooks.rename(files.main, files.rollback)
                else files.deleteDatabaseFiles(files.main)
                state = state.copy(stage = MigrationStage.ORIGINAL_MOVED)
                files.write(state)
                hooks.rename(files.temp, files.main)
                state = state.copy(stage = MigrationStage.INSTALLED)
                files.write(state)
                _progress.value = 80
                AppDatabaseFactory.buildInternal(context, AppDatabaseFactory.DB_NAME, key).useDatabase { reopened ->
                    hooks.reopen(reopened)
                    check(expected == snapshot(reopened)) {
                        "Reopened encrypted database does not match the original"
                    }
                    hooks.checkpoint(reopened)
                }
                currentCoroutineContext().ensureActive()
                Prefs.commitEncryptedMigrationState(context, phrase, key, hooks::commit)
                currentCoroutineContext().ensureActive()
                files.write(state.copy(stage = MigrationStage.COMMITTED))
                DatabaseKeyManager.cacheKey(key)
            } catch (e: Exception) {
                // Read the durable stage in case a rename succeeded before its next stage write.
                withContext(NonCancellable) {
                    DatabaseKeyManager.clearCachedKey()
                    try {
                        val durable = files.read() ?: state.also {
                            check(it.stage == MigrationStage.PREPARING) { "Migration journal is missing" }
                        }
                        if (durable.stage != MigrationStage.COMMITTED) files.rollback(durable)
                    } catch (cleanup: Exception) {
                        if (cleanup !== e) e.addSuppressed(cleanup)
                        _recoveryBlocked.value = true
                        try { Prefs.finishEncryptionAttempt(context, Prefs.EncryptionOutcome.BLOCKED, hooks::commit) }
                        catch (persistence: Exception) { if (persistence !== e) e.addSuppressed(persistence) }
                    }
                }
                throw e
            }
            // Cleanup errors after commitment must never roll back encryption.
            files.finishCommitted()
            _outcome.value = Prefs.encryptionOutcome(context)
            _progress.value = 100
        } catch (e: Exception) {
            Log.e("RealDatabaseMigrator", "Migration failed", e)
            _error.value = "Migration failed: ${e.message}"
            _outcome.value = Prefs.encryptionOutcome(context)
            throw e
        }
    } }

    internal suspend fun recover(context: Context) = withContext(Dispatchers.IO) {
        AppDatabaseFactory.exclusive { recoverLocked(context, MigrationHooks()) }
    }

    private fun recoverLocked(context: Context, hooks: MigrationHooks): RecoveryResult {
        val files = EncryptionRecovery(context, hooks)
        try {
            if (Prefs.isEncrypted(context) && Prefs.loadDerivedKey(context) == null &&
                files.read()?.stage in listOf(null, MigrationStage.COMMITTED, MigrationStage.LEGACY_COMMITTED)) {
                throw MissingDatabaseKey()
            }
            val result = files.recover()
            _conflict.value = null
            _recoveryBlocked.value = false
            _outcome.value = Prefs.encryptionOutcome(context)
            return result
        } catch (e: MissingDatabaseKey) { throw e
        } catch (e: Exception) {
            _recoveryBlocked.value = true
            _error.value = "Recovery failed: ${e.message}"
            try { Prefs.finishEncryptionAttempt(context, Prefs.EncryptionOutcome.BLOCKED, hooks::commit) }
            catch (persistence: Exception) { if (persistence !== e) e.addSuppressed(persistence) }
            _outcome.value = Prefs.EncryptionOutcome.BLOCKED
            throw e
        }
    }

    private suspend fun copyTables(source: AppDatabase, target: AppDatabase) {
        target.categoryDao().insertAll(source.categoryDao().getAllNow())
        target.taskDao().insertAll(source.taskDao().getAllNow())
    }

    private data class Snapshot(val tasks: List<Task>, val categories: List<Category>)

    private suspend fun snapshot(db: AppDatabase) = Snapshot(
        db.taskDao().getAllNow().sortedBy { it.id }, db.categoryDao().getAllNow().sortedBy { it.id }
    )

}
