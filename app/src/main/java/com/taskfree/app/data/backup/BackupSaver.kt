package com.taskfree.app.data.backup

import android.content.Context
import android.net.Uri
import com.taskfree.app.Prefs
import com.taskfree.app.data.database.AppDatabase
import com.taskfree.app.data.repository.Backup
import com.taskfree.app.data.repository.BackupManager
import com.taskfree.app.data.repository.CategoryRepository
import com.taskfree.app.data.repository.TaskRepository
import com.taskfree.app.ui.enc.MnemonicManager
import com.taskfree.app.util.db
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.time.Instant

internal data class BackupStatus(val settings: BackupSettings?, val saving: Boolean = false)

/** Saves the encrypted backup file, one save at a time; [AutoBackup] decides when. */
internal class BackupSaver(
    context: Context,
    private val file: BackupFile = SafBackupFile(context.contentResolver),
    private val database: () -> AppDatabase = { context.db },
    private val now: () -> Instant = Instant::now,
    private val iterations: Int = BackupCrypto.ITERATIONS
) {
    private val context = context.applicationContext
    private val prefs = BackupPrefs(context)
    private val mutex = Mutex()
    // Derived once per process (fresh salt); each save still uses a fresh nonce. Guarded by mutex.
    private var key: Pair<String, BackupCrypto.Key>? = null
    private val _status = MutableStateFlow(BackupStatus(prefs.load()))
    val status: StateFlow<BackupStatus> = _status.asStateFlow()

    val isEnabled: Boolean get() = _status.value.settings != null

    suspend fun enable(uri: Uri, fileName: String) = mutex.withLock {
        prefs.enable(uri, fileName, currentPhraseHash().orEmpty())
        publish()
    }

    suspend fun disable() = mutex.withLock {
        prefs.clear()
        publish()
    }

    /** Accepts the phone's current recovery phrase for future backups. */
    suspend fun usePhrase() = mutex.withLock {
        prefs.usePhrase(currentPhraseHash().orEmpty())
        publish()
    }

    /** Unknown counts as unsaved, so a failed check never skips a save. */
    suspend fun hasUnsavedChanges(): Boolean {
        mutex.withLock {
            val settings = prefs.load() ?: return false
            return try {
                fingerprint(snapshot(), settings) != settings.fingerprint
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                true
            }
        }
    }

    /** Writes the backup if anything changed since the last save, or always when [force]d. */
    suspend fun save(force: Boolean) {
        mutex.withLock {
            val settings = prefs.load() ?: return
            _status.value = BackupStatus(settings, saving = true)
            try {
                attempt(settings, force)
            } finally {
                publish()
            }
        }
    }

    private suspend fun attempt(settings: BackupSettings, force: Boolean) {
        if (!file.canWrite(settings.uri)) return fail(BackupError.Kind.NO_ACCESS)
        // Never switch phrase silently: the phrase the user wrote down must keep opening the file.
        val phrase = Prefs.loadPhrase(context)
        if (phrase == null || MnemonicManager.hashPhrase(phrase) != settings.phraseHash)
            return fail(BackupError.Kind.PHRASE_CHANGED)
        try {
            val backup = snapshot()
            // A fresh install must not overwrite a synced backup before it is restored.
            if (!force && backup.categories.isEmpty() && backup.tasks.isEmpty()) return
            val fingerprint = fingerprint(backup, settings)
            if (!force && fingerprint == settings.fingerprint) return
            val bytes = BackupCrypto.encrypt(BackupManager.encode(backup), keyFor(phrase, settings.phraseHash))
            // Already ciphertext, so finishing the copy after the app is left is safe.
            withContext(NonCancellable + Dispatchers.IO) {
                file.write(settings.uri, bytes)
                prefs.recordSuccess(fingerprint, now())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupWriteException) {
            fail(BackupError.Kind.FAILED, e.detail)
        } catch (e: Exception) {
            fail(BackupError.Kind.FAILED, failureDetail("build", e))
        }
    }

    private suspend fun snapshot(): Backup {
        val db = database()
        return BackupManager.snapshot(CategoryRepository(db), TaskRepository(db))
    }

    /** Ignores the export time; covers the phrase and file so a change to either is saved. */
    private fun fingerprint(backup: Backup, settings: BackupSettings): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(BackupManager.encode(backup.copy(exported_at = "")))
        digest.update(settings.phraseHash.toByteArray())
        digest.update(settings.uri.toString().toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun keyFor(phrase: List<String>, phraseHash: String): BackupCrypto.Key =
        key?.takeIf { it.first == phraseHash }?.second
            ?: BackupCrypto.deriveKey(phrase, iterations = iterations).also { key = phraseHash to it }

    private fun currentPhraseHash(): String? = Prefs.loadPhrase(context)?.let(MnemonicManager::hashPhrase)

    private fun fail(kind: BackupError.Kind, detail: String? = null) =
        prefs.recordError(BackupError(kind, now(), detail))

    private fun publish() {
        _status.value = BackupStatus(prefs.load())
    }
}
