package com.taskfree.app.data

import android.content.Context
import android.content.SharedPreferences
import com.taskfree.app.Prefs
import com.taskfree.app.data.database.AppDatabase
import com.taskfree.app.enc.DatabaseKeyManager
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Internal failure seams; production always uses checked file operations and synchronous commits. */
internal open class MigrationHooks {
    open fun rename(from: File, to: File) {
        check(!to.exists()) { "Refusing to overwrite ${to.name}" }
        check(from.renameTo(to)) { "Cannot rename ${from.name} to ${to.name}" }
    }

    open fun delete(file: File) {
        check(!file.exists() || file.delete()) { "Cannot delete ${file.name}" }
    }

    open fun writeJournal(file: File, value: String) {
        val pending = File(file.path + ".new")
        FileOutputStream(pending).use {
            it.write(value.toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        Files.move(pending.toPath(), file.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    open fun commit(name: String, editor: SharedPreferences.Editor): Boolean = editor.commit()

    open fun checkpoint(db: AppDatabase) {
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use {
            check(it.moveToFirst() && it.getInt(0) == 0 && it.getInt(1) == it.getInt(2)) {
                "Database checkpoint did not finish"
            }
        }
    }

    open fun reopen(db: AppDatabase) {
        db.openHelper.writableDatabase.query("PRAGMA integrity_check").use {
            check(it.moveToFirst() && it.getString(0) == "ok") { "Database integrity check failed" }
        }
    }

    open fun verifyInstalled(context: Context) {
        check(context.getDatabasePath(AppDatabaseFactory.DB_NAME).exists()) { "Installed database is missing" }
        val key = if (Prefs.isEncrypted(context)) requireNotNull(Prefs.loadDerivedKey(context)) {
            "Committed encryption key is missing"
        } else null
        AppDatabaseFactory.buildInternal(context, AppDatabaseFactory.DB_NAME, key).useDatabase { reopen(it) }
    }
}

internal enum class MigrationStage {
    PREPARING, READY, ORIGINAL_MOVED, INSTALLED, ROLLING_BACK, COMMITTED,
    LEGACY_SELECTING, LEGACY_INSTALLING, LEGACY_ROLLING_BACK, LEGACY_RESTORED, LEGACY_COMMITTED
}

internal data class MigrationJournal(val stage: MigrationStage, val hadOriginal: Boolean)
internal enum class RecoveryResult { READY, ROLLED_BACK, CLEANUP_WARNING, CONFLICT }
internal enum class LegacyChoice { MAIN, BACKUP }
internal data class LegacyCandidate(val tasks: Int, val categories: Int, val key: ByteArray?)
internal data class LegacyConflict(val main: LegacyCandidate?, val backup: LegacyCandidate?) {
    val suggested: LegacyChoice? get() = when {
        main == null || backup == null -> null
        main.tasks > 0 && backup.tasks == 0 -> LegacyChoice.MAIN
        backup.tasks > 0 && main.tasks == 0 -> LegacyChoice.BACKUP
        else -> null
    }
}

/** All callers hold AppDatabaseFactory's gate. The journal never contains secrets. */
internal class EncryptionRecovery(
    private val context: Context,
    private val hooks: MigrationHooks = MigrationHooks()
) {
    val main = context.getDatabasePath(AppDatabaseFactory.DB_NAME)
    val temp = context.getDatabasePath(AppDatabaseFactory.TEMP_DB_NAME)
    val rollback = File(context.noBackupFilesDir, "encryption-rollback.db")
    val displaced = File(context.noBackupFilesDir, "encryption-uncommitted.db")
    val journal = File(context.noBackupFilesDir, "encryption-migration.journal")
    val legacy = context.getDatabasePath("checklists_backup.db")
    val selected = File(context.noBackupFilesDir, "encryption-selected.db")
    private val preview = File(context.noBackupFilesDir, "encryption-preview.db")
    // Key derivation is slow and runs while the gate is held; derive at most once per instance.
    private val phraseKey: ByteArray? by lazy {
        Prefs.loadPhrase(context)?.let(DatabaseKeyManager::deriveKeyFromPhrase)
    }

    fun read(): MigrationJournal? {
        if (!journal.exists()) return null
        val parts = journal.readText().split('\n')
        check(parts.size == 3 && parts[0] == "1" && parts[2] in listOf("true", "false")) {
            "Invalid encryption migration journal; recovery required"
        }
        return MigrationJournal(MigrationStage.valueOf(parts[1]), parts[2].toBoolean())
    }

    fun write(state: MigrationJournal) {
        hooks.writeJournal(journal, "1\n${state.stage}\n${state.hadOriginal}")
    }

    fun recover(): RecoveryResult {
        val state = read()
        if (state == null) {
            check((listOf(rollback, displaced, selected) + sidecars(rollback) + sidecars(displaced) + sidecars(selected)).none { it.exists() }) {
                "Unresolved encryption recovery files"
            }
            // An interrupted first journal write cannot have reached any database mutation.
            // A leftover pending file must still be removable before allowing normal access.
            hooks.delete(File(journal.path + ".new"))
            if (!legacy.exists()) {
                check(main.exists() || (sidecars(main) + temp + sidecars(temp)).none { it.exists() }) {
                    "Unresolved database files; refusing to create an empty database"
                }
                if (Prefs.attemptStarted(context) && !Prefs.isEncrypted(context)) {
                    Prefs.finishEncryptionAttempt(context, Prefs.EncryptionOutcome.ROLLED_BACK, hooks::commit)
                    return RecoveryResult.ROLLED_BACK
                }
                return RecoveryResult.READY
            }
            if (main.exists()) return RecoveryResult.CONFLICT
            check(sidecars(main).none { it.exists() }) { "Conflicting database sidecars; files preserved" }
            return selectLegacy(LegacyChoice.BACKUP)
        } else when (state.stage) {
            MigrationStage.COMMITTED, MigrationStage.LEGACY_COMMITTED -> return finishCommitted()
            MigrationStage.LEGACY_SELECTING, MigrationStage.LEGACY_INSTALLING,
            MigrationStage.LEGACY_ROLLING_BACK, MigrationStage.LEGACY_RESTORED -> {
                restoreLegacySelection(state)
                return if (main.exists() && legacy.exists()) RecoveryResult.CONFLICT else recover()
            }
            else -> {
                rollback(state)
                return RecoveryResult.ROLLED_BACK
            }
        }
    }

    fun rollback(state: MigrationJournal) {
        // Validate before changing the journal or any file. A missing original must never
        // be mistaken for a successful rollback and opened as an empty database.
        if (state.hadOriginal && !rollback.exists()) {
            check(main.exists() && state.stage in setOf(
                MigrationStage.PREPARING, MigrationStage.READY, MigrationStage.ROLLING_BACK
            )) { "Original database is unavailable; recovery required" }
        }
        if (state.hadOriginal) AppDatabaseFactory.requirePlaintextHeader(if (rollback.exists()) rollback else main)
        write(state.copy(stage = MigrationStage.ROLLING_BACK))
        DatabaseKeyManager.clearCachedKey()
        if (rollback.exists()) {
            preserveUncommittedDatabase()
            hooks.rename(rollback, main)
        } else if (!state.hadOriginal) {
            preserveUncommittedDatabase()
        }
        persistPlaintext()
        deleteDatabaseFiles(temp)
        deleteDatabaseFiles(displaced)
        Prefs.finishEncryptionAttempt(context, Prefs.EncryptionOutcome.ROLLED_BACK, hooks::commit)
        clearJournal()
    }

    private fun preserveUncommittedDatabase() {
        // Keep the installed copy if restoring the original fails. Moving sidecars
        // first lets recovery resume after any individual rename without data loss.
        (sidecars(main) + main).zip(sidecars(displaced) + displaced).forEach { (from, to) ->
            if (from.exists()) hooks.rename(from, to)
        }
    }

    private fun persistPlaintext() {
        DatabaseKeyManager.clearCachedKey()
        Prefs.commitPlaintextMigrationState(context, hooks::commit)
    }

    fun finishCommitted(): RecoveryResult {
        val legacyCommit = read()?.stage == MigrationStage.LEGACY_COMMITTED
        check(main.exists() && (legacyCommit || Prefs.isEncrypted(context))) { "Committed database is unavailable" }
        hooks.verifyInstalled(context)
        // A cleanup error leaves COMMITTED on disk. It must never roll back encryption.
        return try {
            deleteDatabaseFiles(rollback)
            deleteDatabaseFiles(temp)
            if (legacyCommit) {
                deleteDatabaseFiles(legacy)
                deleteDatabaseFiles(displaced)
                deleteDatabaseFiles(selected)
                Prefs.forgetLegacySettings(context)
            }
            Prefs.finishEncryptionAttempt(context, null, hooks::commit)
            clearJournal()
            RecoveryResult.READY
        } catch (e: Exception) {
            Prefs.finishEncryptionAttempt(context, Prefs.EncryptionOutcome.CLEANUP_WARNING, hooks::commit)
            RecoveryResult.CLEANUP_WARNING
        }
    }

    fun inspectLegacy() = LegacyConflict(inspect(main), inspect(legacy))

    /** No Room builders or migrations during previews; WAL/journal belong to the copy. */
    private fun inspect(original: File): LegacyCandidate? {
        if (!original.exists()) return null
        val keys = mutableListOf<ByteArray?>()
        if (runCatching { AppDatabaseFactory.requirePlaintextHeader(original) }.isSuccess) keys.add(null)
        else {
            runCatching { Prefs.loadDerivedKey(context) }.getOrNull()?.let(keys::add)
            phraseKey?.let(keys::add)
        }
        for (key in keys) {
            try {
                copyDatabase(original, preview)
                val candidate = if (key == null) {
                    AppDatabaseFactory.requirePlaintextHeader(preview)
                    android.database.sqlite.SQLiteDatabase.openDatabase(preview.path, null,
                        android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
                        readCounts(key) { db.rawQuery(it, null) }
                    }
                } else {
                    net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(preview.path, key, null,
                        net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READWRITE, null).use { db ->
                        readCounts(key) { db.rawQuery(it, emptyArray<String>()) }
                    }
                }
                return candidate
            } catch (_: Exception) {
                // Failed inspection is unavailable, never a zero-task candidate.
            } finally {
                deleteDatabaseFiles(preview)
            }
        }
        return null
    }

    private fun readCounts(key: ByteArray?, query: (String) -> android.database.Cursor): LegacyCandidate {
        query("PRAGMA integrity_check").use {
            check(it.moveToFirst() && it.getString(0) == "ok") { "Legacy database integrity check failed" }
        }
        fun count(table: String) = query("SELECT COUNT(*) FROM $table").use {
            check(it.moveToFirst())
            it.getInt(0)
        }
        return LegacyCandidate(count("Task"), count("Category"), key)
    }

    private fun copyDatabase(from: File, to: File) {
        deleteDatabaseFiles(to)
        (listOf(from) + sidecars(from)).zip(listOf(to) + sidecars(to)).forEach { (source, target) ->
            if (source.exists()) source.inputStream().use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output); output.fd.sync() }
            }
        }
    }

    fun selectLegacy(choice: LegacyChoice): RecoveryResult {
        AppDatabaseFactory.beginMigrationLocked()
        val source = if (choice == LegacyChoice.MAIN) main else legacy
        val candidate = requireNotNull(inspect(source)) { "Selected database is unavailable" }
        val state = MigrationJournal(MigrationStage.LEGACY_SELECTING, main.exists())
        Prefs.preserveLegacySettings(context)
        write(state)
        try {
            copyDatabase(source, selected)
            AppDatabaseFactory.buildInternal(context, selected.path, candidate.key).useDatabase {
                hooks.reopen(it)
                hooks.checkpoint(it)
            }
            removeCheckpointedSidecars(selected)
            // Original main (including sidecars) stays intact in displaced until commitment.
            preserveUncommittedDatabase()
            write(state.copy(stage = MigrationStage.LEGACY_INSTALLING))
            hooks.rename(selected, main)
            AppDatabaseFactory.buildInternal(context, AppDatabaseFactory.DB_NAME, candidate.key).useDatabase {
                hooks.reopen(it)
                hooks.checkpoint(it)
            }
            Prefs.commitSelectedDatabase(context, candidate.key)
            write(state.copy(stage = MigrationStage.LEGACY_COMMITTED))
        } catch (e: Exception) {
            if (read()?.stage != MigrationStage.LEGACY_COMMITTED) {
                try { restoreLegacySelection(read() ?: state) } catch (recovery: Exception) {
                    e.addSuppressed(recovery)
                }
            }
            throw e
        }
        return finishCommitted()
    }

    private fun restoreLegacySelection(state: MigrationJournal) {
        // A SELECTING interruption can split sidecars between main and displaced.
        if (state.stage == MigrationStage.LEGACY_SELECTING) {
            (sidecars(displaced) + displaced).zip(sidecars(main) + main).forEach { (from, to) ->
                if (from.exists()) hooks.rename(from, to)
            }
        } else if (state.stage != MigrationStage.LEGACY_RESTORED) {
            check(!state.hadOriginal || displaced.exists()) { "Original legacy main is missing" }
            write(state.copy(stage = MigrationStage.LEGACY_ROLLING_BACK))
            // Keep the source until restoration is durably complete. A killed copy
            // can always be repeated, including after a partially restored WAL.
            if (state.hadOriginal) copyDatabase(displaced, main) else deleteDatabaseFiles(main)
        }
        Prefs.restoreLegacySettings(context)
        write(state.copy(stage = MigrationStage.LEGACY_RESTORED))
        deleteDatabaseFiles(displaced)
        deleteDatabaseFiles(selected)
        clearJournal()
    }

    fun resetFiles() {
        listOf(main, temp, legacy, rollback, displaced, selected, preview).forEach(::deleteDatabaseFiles)
        clearJournal()
    }

    fun deleteDatabaseFiles(file: File) {
        sidecars(file).forEach(hooks::delete)
        hooks.delete(file)
    }

    fun removeCheckpointedSidecars(file: File) {
        check(!File(file.path + "-wal").let { it.exists() && it.length() > 0 }) {
            "Uncheckpointed database writes remain"
        }
        check(!File(file.path + "-journal").let { it.exists() && it.length() > 0 }) {
            "Database journal still contains pending writes"
        }
        sidecars(file).forEach(hooks::delete)
    }

    private fun clearJournal() {
        hooks.delete(File(journal.path + ".new"))
        hooks.delete(journal)
    }

    private fun sidecars(file: File) = listOf("-wal", "-shm", "-journal").map { File(file.path + it) }
}
