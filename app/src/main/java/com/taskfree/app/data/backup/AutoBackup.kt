package com.taskfree.app.data.backup

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * Decides when to save the backup file: a minute after edits stop, when the app comes to the
 * foreground, when the user leaves it, and on request. [BackupSaver] does each save.
 */
internal object AutoBackup {
    private const val TAG = "AutoBackup"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @SuppressLint("StaticFieldLeak") // Holds only the application context.
    private var saver: BackupSaver? = null
    private var watcher: Job? = null
    // Only decides whether leaving starts the service; a wrong value never skips a save.
    @Volatile private var dirty = false

    @MainThread
    fun status(context: Context): StateFlow<BackupStatus> = saver(context).status

    /** Call once the database is ready, each time the app comes to the foreground. */
    @MainThread
    fun onForeground(context: Context) {
        if (!ready() || !saver(context).isEnabled) return
        watch(context)
        launchSave(context, force = false, hold = false)
    }

    @MainThread
    fun onLeaving(context: Context) {
        if (!ready() || !saver(context).isEnabled) return
        val failed = saver(context).status.value.settings?.error != null
        launchSave(context, force = false, hold = dirty || failed)
    }

    @MainThread
    fun backUpNow(context: Context) {
        if (ready()) launchSave(context, force = true, hold = true)
    }

    /** [uri] must already hold a persisted read and write grant. */
    @MainThread
    fun enable(context: Context, uri: Uri, fileName: String) {
        if (!ready()) return
        saver(context).status.value.settings?.uri?.takeIf { it != uri }?.let { releaseGrant(context, it) }
        watch(context)
        launchSave(context, force = true, hold = true) { enable(uri, fileName) }
    }

    /** The file itself is left in place. */
    @MainThread
    fun disable(context: Context) {
        val saver = saver(context)
        saver.status.value.settings?.uri?.let { releaseGrant(context, it) }
        watcher?.cancel()
        watcher = null
        scope.launch { saver.disable() }
    }

    @MainThread
    fun usePhrase(context: Context) {
        if (ready()) launchSave(context, force = true, hold = true) { usePhrase() }
    }

    private fun ready() = RealDatabaseMigrator.startup.value == DatabaseStartup.READY

    private fun saver(context: Context): BackupSaver =
        saver ?: BackupSaver(context.applicationContext).also { saver = it }

    /** Starts the service first, if asked, because Android only allows that while the app is visible. */
    private fun launchSave(
        context: Context,
        force: Boolean,
        hold: Boolean,
        prepare: suspend BackupSaver.() -> Unit = {}
    ) {
        val saver = saver(context)
        val held = hold && BackupSaveService.hold(context)
        scope.launch {
            try {
                saveAndRefresh(saver, force, prepare)
            } finally {
                if (held) BackupSaveService.release(context)
            }
        }
    }

    private suspend fun saveAndRefresh(
        saver: BackupSaver,
        force: Boolean,
        prepare: suspend BackupSaver.() -> Unit = {}
    ) {
        try {
            saver.prepare()
            saver.save(force)
            dirty = saver.hasUnsavedChanges()
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
        val saver = saver(context)
        watcher = scope.launch {
            try {
                context.db.invalidationTracker.createFlow("Task", "Category", emitInitialState = false)
                    .collectLatest {
                        dirty = true
                        delay(1.seconds) // Let a burst of writes finish, then ignore writes that changed nothing.
                        dirty = saver.hasUnsavedChanges()
                        delay(59.seconds)
                        if (ready()) scope.launch { saveAndRefresh(saver, force = false) }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Stopped watching for changes", e)
            }
        }
    }

    private fun releaseGrant(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }
}
