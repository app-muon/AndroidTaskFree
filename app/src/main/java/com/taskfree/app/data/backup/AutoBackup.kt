package com.taskfree.app.data.backup

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.MainThread
import com.taskfree.app.data.DatabaseStartup
import com.taskfree.app.data.RealDatabaseMigrator
import com.taskfree.app.util.db
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlin.time.Duration.Companion.seconds

/**
 * Decides when to save the backup file: a minute after edits stop, when the app comes to the
 * foreground, when the user leaves it, and on request. [BackupSaver] does each save.
 */
internal object AutoBackup {
    enum class EnableResult { STARTED, BUSY, NOT_READY, RESTART_REQUIRED }
    private const val TAG = "AutoBackup"
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scope = processScope
    @SuppressLint("StaticFieldLeak") // Holds only the application context.
    private var saver: BackupSaver? = null
    private var watcher: Job? = null
    private var setupJob: Job? = null
    // Only decides whether leaving starts the service; a wrong value never skips a save.
    @Volatile private var automaticSaveNeeded = true

    @MainThread
    fun status(context: Context): StateFlow<BackupStatus> = saver(context).status

    /** Call once the database is ready, each time the app comes to the foreground. */
    @MainThread
    fun onForeground(context: Context) {
        if (!ready() || !saver(context).isEnabled) return
        watch(context)
        launchSave(context, hold = false) { save(false) }
    }

    @MainThread
    fun onLeaving(context: Context) {
        if (!ready() || !saver(context).isEnabled) return
        val saver = saver(context)
        val hold = saver.status.value.saving || (saver.canSaveAutomatically() && automaticSaveNeeded)
        launchSave(context, hold = hold) { save(false) }
    }

    @MainThread
    fun backUpNow(context: Context) {
        if (ready() && saver(context).status.value.canMutate)
            launchSave(context, hold = true) { save(true) }
    }

    /** Takes the folder grant and replaces the destination only after a verified first save. */
    @MainThread
    fun enable(context: Context, uri: Uri): EnableResult {
        setupRejection(context)?.let { return it }
        watch(context)
        setupJob = launchSave(context, hold = true) { enable(uri) }
        return EnableResult.STARTED
    }

    /** Also checked when a cloud-folder picker returns, before showing its confirmation. */
    @MainThread
    fun setupRejection(context: Context): EnableResult? = when {
        saver(context).status.value.restartRequired -> EnableResult.RESTART_REQUIRED
        !ready() -> EnableResult.NOT_READY
        setupJob?.isCompleted == false || saver(context).status.value.saving -> EnableResult.BUSY
        else -> null
    }

    /** The file itself is left in place. */
    @MainThread
    fun disable(context: Context) {
        val saver = saver(context)
        if (!saver.status.value.canMutate) return
        watcher?.cancel()
        watcher = null
        launchSave(context, hold = false) { disable() }
    }

    @MainThread
    fun usePhrase(context: Context) {
        if (ready() && saver(context).status.value.canMutate)
            launchSave(context, hold = true) { usePhrase() }
    }

    private fun ready() = RealDatabaseMigrator.startup.value == DatabaseStartup.READY

    /** Stop and join the watcher, then let existing saves finish before database/grant teardown. */
    internal suspend fun resetProcessForTesting() {
        watcher?.cancelAndJoin()
        scope.coroutineContext[Job]!!.children.toList().joinAll()
        watcher = null
        setupJob = null
        saver = null
        automaticSaveNeeded = true
        scope = processScope
    }

    /** Called only between test process resets, before any work starts. */
    internal fun configureForTesting(backupSaver: BackupSaver, saveScope: CoroutineScope = processScope) {
        saver = backupSaver
        scope = saveScope
    }

    private fun saver(context: Context): BackupSaver =
        saver ?: BackupSaver(context.applicationContext).also { saver = it }

    /** Starts the service first, if asked, because Android only allows that while the app is visible. */
    private fun launchSave(
        context: Context,
        hold: Boolean,
        operation: suspend BackupSaver.() -> Unit
    ): Job {
        val appContext = context.applicationContext
        val saver = saver(appContext)
        val held = hold && BackupSaveService.hold(appContext)
        return scope.launch {
            saveAndRefresh(saver, operation)
        }.also { job ->
            job.invokeOnCompletion { if (held) BackupSaveService.release(appContext) }
        }
    }

    private suspend fun saveAndRefresh(
        saver: BackupSaver,
        operation: suspend BackupSaver.() -> Unit
    ) {
        try {
            saver.operation()
            automaticSaveNeeded = saver.needsAutomaticSave()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // save() records its own failures; this only keeps an unexpected one from crashing the app.
            Log.e(TAG, "Backup save failed", e)
        }
    }

    @MainThread
    private fun watch(context: Context) {
        if (watcher?.isActive == true) return
        val appContext = context.applicationContext
        val saver = saver(appContext)
        watcher = scope.launch {
            try {
                appContext.db.invalidationTracker.createFlow("Task", "Category", "TaskNote", emitInitialState = false)
                    .collectLatest {
                        saver.databaseChanged()
                        automaticSaveNeeded = true
                        delay(1.seconds) // Let a burst of writes finish, then ignore writes that changed nothing.
                        automaticSaveNeeded = saver.needsAutomaticSave()
                        delay(59.seconds)
                        if (ready()) scope.launch { saveAndRefresh(saver) { save(false) } }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Stopped watching for changes", e)
            }
        }
    }

}
