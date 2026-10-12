package com.taskfree.app.data.backup

import android.content.Context
import android.net.Uri
import com.taskfree.app.Prefs
import com.taskfree.app.data.database.AppDatabase
import com.taskfree.app.data.repository.Backup
import com.taskfree.app.data.repository.BackupManager
import com.taskfree.app.ui.enc.MnemonicManager
import com.taskfree.app.util.db
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

internal data class BackupStatus(
    val settings: BackupSettings?,
    val saving: Boolean = false,
    val operationError: BackupError? = null,
    val restartRequired: Boolean = false
) {
    val canMutate: Boolean get() = !saving && !restartRequired
    val error: BackupError? get() = settings?.error
    val pending: Boolean get() = error != null || settings?.pending == true
}

/** All writes, settings changes and grant lifetimes share this mutex. */
internal class BackupSaver(
    context: Context,
    private val file: BackupFile = SafBackupFile(context.contentResolver),
    private val database: () -> AppDatabase = { context.db },
    private val now: () -> Instant = Instant::now,
    private val iterations: Int = BackupCrypto.ITERATIONS,
    private val prefs: BackupSettingsStore = BackupPrefs(context)
) {
    private val context = context.applicationContext
    private val mutex = Mutex()
    private var key: Pair<String, BackupCrypto.Key>? = null
    // Never reload SharedPreferences in this process: a failed commit can still change its in-memory map.
    private var trusted = prefs.load()
    @Volatile private var blocked = false
    private val databaseRevision = AtomicLong()
    @Volatile private var oversizedRevision: Long? = null
    private var operationError: BackupError? = null
    private val _status = MutableStateFlow(BackupStatus(trusted))
    val status: StateFlow<BackupStatus> = _status.asStateFlow()
    val isEnabled: Boolean get() = status.value.settings?.needsMigration == false && !blocked

    /** Called before the watcher's debounce, including while a snapshot/save is in progress. */
    fun databaseChanged() { databaseRevision.incrementAndGet() }

    /** Cheap enough for lifecycle/service decisions; the same policy gates snapshots below. */
    fun canSaveAutomatically(): Boolean = automaticRetryAllowed(status.value.settings)

    private fun automaticRetryAllowed(settings: BackupSettings?): Boolean {
        if (settings == null || settings.needsMigration || blocked) return false
        return when (settings.error?.kind) {
            BackupError.Kind.ACTIVE_DAMAGED, BackupError.Kind.PHRASE_CHANGED -> false
            BackupError.Kind.TOO_LARGE -> oversizedRevision != databaseRevision.get()
            BackupError.Kind.NO_ACCESS -> runCatching { file.canWrite(settings.uri) }.getOrDefault(false)
            else -> true
        }
    }

    /** Keeps the current destination until both newly created files are verified and committed. */
    suspend fun enable(uri: Uri) = operation {
        val previous = trusted
        val created = mutableSetOf<Uri>()
        var commitmentAttempted = false
        try {
            file.retain(uri)
            if (!file.canWrite(uri)) return@operation setupFailed(BackupError.Kind.NO_ACCESS)
            val phrase = Prefs.loadPhrase(context)
                ?: return@operation setupFailed(BackupError.Kind.PHRASE_CHANGED)
            val phraseHash = MnemonicManager.hashPhrase(phrase)
            // Changing folders must not silently accept a changed recovery phrase either.
            if (previous != null && previous.phraseHash != phraseHash)
                return@operation setupFailed(BackupError.Kind.PHRASE_CHANGED)
            val backup = BackupManager.snapshot(database())
            val bytes = payload(backup, phrase, phraseHash)
            val setupDate = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss'Z'", Locale.ROOT)
                .withZone(ZoneOffset.UTC).format(now())
            val slots = listOf("A", "B").map { letter ->
                val name = "TaskFree-$setupDate-$letter.tfbackup"
                val slot = file.create(uri, name)
                // Track each returned document before any later creation or validation can fail.
                val existing = previous?.slots?.any { it.uri == slot.uri } == true || slot.uri == previous?.uri
                if (!existing) created += slot.uri
                check(!existing)
                currentCoroutineContext().ensureActive()
                slot
            }
            check(slots[0].uri != slots[1].uri)
            val verified = slots.map {
                writeVerified(it, bytes).also { currentCoroutineContext().ensureActive() }
            }
            val candidate = BackupSettings(uri, phraseHash, slots = verified, activeSlot = 1,
                fingerprint = fingerprint(backup, phraseHash), savedAt = now(), pending = false)
            currentCoroutineContext().ensureActive()
            commitmentAttempted = true
            if (commit(candidate)) {
                operationError = null
                previous?.uri?.takeIf { it != uri }?.let { runCatching { file.release(it) } }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            operationError = errorFor(e)
        } finally {
            // Once commitment is attempted either configuration may be durable. Keep both pairs/grants.
            if (!commitmentAttempted) withContext(NonCancellable) {
                created.forEach { runCatching { file.delete(it) } }
                if (trusted?.uri != uri) runCatching { file.release(uri) }
            }
        }
    }

    /** Files stay in the folder. A failed settings commit retains access and stops all later writes. */
    suspend fun disable() = operation {
        val old = trusted
        if (commit(null)) {
            operationError = null
            old?.uri?.let(file::release)
        }
    }

    suspend fun usePhrase() = operation {
        val settings = trusted ?: return@operation
        val phrase = Prefs.loadPhrase(context)
            ?: return@operation fail(BackupError.Kind.PHRASE_CHANGED)
        if (commit(settings.copy(phraseHash = MnemonicManager.hashPhrase(phrase), pending = true,
                error = settings.error?.takeUnless { it.kind == BackupError.Kind.PHRASE_CHANGED }))) {
            if (!settings.needsMigration) attempt(force = false)
        }
    }

    /** Repair work is retryable automatically only while nonempty data can safely be saved. */
    suspend fun needsAutomaticSave(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val settings = trusted ?: return@withLock false
            if (!automaticRetryAllowed(settings)) return@withLock false
            try {
                val backup = BackupManager.snapshot(database())
                if (backup.categories.isEmpty() && backup.tasks.isEmpty()) return@withLock false
                settings.pending || settings.error != null ||
                    fingerprint(backup, settings.phraseHash) != settings.fingerprint
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                true
            }
        }
    }

    suspend fun save(force: Boolean) = operation { attempt(force) }

    private suspend fun operation(block: suspend () -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (blocked) return@withLock
            _status.value = BackupStatus(trusted, saving = true, operationError = operationError)
            try {
                block()
            } finally {
                _status.value = BackupStatus(trusted, operationError = operationError, restartRequired = blocked)
            }
        }
    }

    private suspend fun attempt(force: Boolean) {
        val settings = trusted ?: return
        if (settings.needsMigration) return // The legacy document must never be opened for writing.
        if (!force && !automaticRetryAllowed(settings)) return
        // Capture before the snapshot: an invalidation during this attempt permits another try.
        val revision = databaseRevision.get()
        try {
            val backup = BackupManager.snapshot(database())
            if (!force && backup.categories.isEmpty() && backup.tasks.isEmpty()) return
            if (!file.canWrite(settings.uri)) return fail(BackupError.Kind.NO_ACCESS)
            val phrase = Prefs.loadPhrase(context)
            if (phrase == null || MnemonicManager.hashPhrase(phrase) != settings.phraseHash)
                return fail(BackupError.Kind.PHRASE_CHANGED)
            val fingerprint = fingerprint(backup, settings.phraseHash)
            if (!force && !settings.pending && settings.error == null &&
                fingerprint == settings.fingerprint) return
            val active = settings.active
            if (active?.digest?.matches(Regex("[0-9a-f]{64}")) != true ||
                active.size !in 1..BackupManager.MAX_FILE_BYTES.toLong() ||
                settings.slots.map { it.uri }.distinct().size != 2)
                return fail(BackupError.Kind.ACTIVE_DAMAGED)
            try {
                file.verify(active.uri, active.size, active.digest)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupVerificationMismatchException) {
                return fail(BackupError.Kind.ACTIVE_DAMAGED)
            } catch (e: Exception) {
                val error = errorFor(e)
                return fail(error.kind, error.detail)
            }
            val bytes = payload(backup, phrase, settings.phraseHash)
            // Persist pending work before opening the inactive file. Process death cannot hide a failed forced save.
            if (!settings.pending && !commit(settings.copy(pending = true))) return
            withContext(NonCancellable) {
                val inactive = 1 - settings.activeSlot
                val written = writeVerified(settings.slots[inactive], bytes)
                val updated = settings.slots.toMutableList().apply { this[inactive] = written }
                commit(settings.copy(slots = updated, activeSlot = inactive, fingerprint = fingerprint,
                    savedAt = now(), pending = false, error = null))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = errorFor(e)
            if (error.kind == BackupError.Kind.TOO_LARGE) oversizedRevision = revision
            fail(error.kind, error.detail)
        }
    }

    private fun payload(backup: Backup, phrase: List<String>, phraseHash: String): ByteArray {
        val plain = BackupManager.encode(backup)
        BackupManager.checkOutputSize(plain.size.toLong() + BackupCrypto.OVERHEAD_BYTES)
        return BackupCrypto.encrypt(plain, keyFor(phrase, phraseHash))
    }

    private fun writeVerified(slot: BackupSlot, bytes: ByteArray): BackupSlot {
        val digest = bytes.backupDigest()
        file.write(slot.uri, bytes)
        try {
            file.verify(slot.uri, bytes.size.toLong(), digest)
        } catch (e: BackupVerificationMismatchException) {
            throw BackupWriteException(failureDetail("verification", e), e)
        }
        return slot.copy(digest = digest, size = bytes.size.toLong())
    }

    private fun fingerprint(backup: Backup, phraseHash: String): String =
        MessageDigest.getInstance("SHA-256").apply {
            update(BackupManager.encode(backup.copy(exported_at = "")))
            update(phraseHash.toByteArray())
        }.digest().toBackupHex()

    private fun keyFor(phrase: List<String>, phraseHash: String): BackupCrypto.Key =
        key?.takeIf { it.first == phraseHash }?.second
            ?: BackupCrypto.deriveKey(phrase, iterations = iterations).also { key = phraseHash to it }

    private fun commit(settings: BackupSettings?): Boolean {
        if (blocked) return false
        if (runCatching { prefs.commit(settings) }.getOrDefault(false)) {
            trusted = settings
            return true
        }
        blocked = true
        return false
    }

    private fun fail(kind: BackupError.Kind, detail: String? = null) {
        val settings = trusted ?: return
        commit(settings.copy(error = BackupError(kind, now(), detail), pending = true))
    }

    private fun setupFailed(kind: BackupError.Kind) {
        operationError = BackupError(kind, now())
    }

    private fun errorFor(e: Exception): BackupError = when {
        generateSequence<Throwable>(e) { it.cause }.any { it is SecurityException } ->
            BackupError(BackupError.Kind.NO_ACCESS, now())
        e is BackupManager.BackupValidationException -> BackupError(BackupError.Kind.TOO_LARGE, now())
        e is BackupWriteException -> BackupError(BackupError.Kind.FAILED, now(), e.detail)
        else -> BackupError(BackupError.Kind.FAILED, now(), failureDetail("build", e))
    }
}
