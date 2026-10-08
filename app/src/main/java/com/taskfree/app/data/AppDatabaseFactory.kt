package com.taskfree.app.data

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.room.migration.Migration
import com.taskfree.app.Prefs
import com.taskfree.app.data.database.AppDatabase
import com.taskfree.app.data.database.MIGRATION_10_11
import com.taskfree.app.data.database.MIGRATION_11_12
import com.taskfree.app.data.database.MIGRATION_13_14
import com.taskfree.app.data.database.MIGRATION_14_15
import com.taskfree.app.data.database.MIGRATION_15_16
import com.taskfree.app.data.database.MIGRATION_16_17
import com.taskfree.app.data.database.MIGRATION_17_18
import com.taskfree.app.data.database.MIGRATION_18_19
import com.taskfree.app.data.database.MIGRATION_FIX_RECURRENCE
import com.taskfree.app.enc.DatabaseKeyManager
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.util.concurrent.Semaphore

object AppDatabaseFactory {
    internal const val DB_NAME = "checklists.db"
    internal const val TEMP_DB_NAME = "checklists_temp.db"

    // A semaphore can span coroutine thread switches; a thread-owned monitor cannot.
    internal val gate = Semaphore(1, true)
    @Volatile private var instance: AppDatabase? = null
    @Volatile private var accessDisabled = false
    // Sticky for the lifetime of the process, including after clearInstance().
    private var normalDatabaseOpened = false

    private val migrations: Array<Migration> = arrayOf(
        MIGRATION_10_11, MIGRATION_11_12, MIGRATION_FIX_RECURRENCE,
        MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17,
        MIGRATION_17_18, MIGRATION_18_19
    )

    fun getDatabase(@Suppress("UNUSED_PARAMETER") context: Context): AppDatabase {
        check(!accessDisabled) { "Database unavailable during encryption or recovery" }
        return instance ?: error("Database is not ready; await application startup first")
    }

    /** Only called by the migrator after recovery, with gate held. */
    internal fun openNormalLocked(context: Context): AppDatabase {
        check(!accessDisabled) { "Normal database access is disabled in this process" }
        return instance ?: run {
            val key = if (Prefs.isEncrypted(context)) {
                DatabaseKeyManager.loadDerivedKey(context) ?: DatabaseKeyManager.getCachedKey()
                    ?: error("No encryption key available")
            } else null
            check(key == null || context.getDatabasePath(DB_NAME).exists()) {
                "Encrypted database is missing; refusing to create an empty database"
            }
            val db = buildInternal(context, DB_NAME, key)
            try {
                db.openHelper.writableDatabase // Do not cache an unverified, lazy handle.
                normalDatabaseOpened = true
                instance = db
                key?.let(DatabaseKeyManager::cacheKey)
                db
            } catch (e: Exception) {
                try { db.close() } catch (close: Exception) {
                    if (close !== e) e.addSuppressed(close)
                }
                throw e
            }
        }
    }

    /** Dedicated builders used only while the migration gate is held (or in native tests). */
    internal fun buildInternal(context: Context, name: String, key: ByteArray? = null): AppDatabase {
        if (key == null) requirePlaintextHeader(context.getDatabasePath(name))
        val builder = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
            .addMigrations(*migrations)
        // SQLCipher never deletes on corruption; the framework helper must be told not to.
        builder.openHelperFactory(
            if (key != null) SupportOpenHelperFactory(key.copyOf())
            else NonDeletingOpenHelperFactory(FrameworkSQLiteOpenHelperFactory())
        )
        return builder.build()
    }

    internal fun createTempEncryptedDatabase(context: Context, key: ByteArray): AppDatabase =
        buildInternal(context, TEMP_DB_NAME, key)

    internal fun closeInternal() {
        try { instance?.close() } finally { instance = null }
    }

    fun clearInstance() = exclusive { closeInternal() }

    internal fun disableAccess() { accessDisabled = true }

    internal fun beginMigrationLocked() {
        check(!normalDatabaseOpened) { "Encryption requires a fresh process: a normal database was already opened" }
        accessDisabled = true
    }

    internal fun allowNormalAccessLocked() { accessDisabled = false }

    /** Simulates process death in tests; production must restart instead. */
    internal fun resetProcessForTesting() = exclusive {
        closeInternal()
        normalDatabaseOpened = false
        accessDisabled = false
    }

    internal fun requirePlaintextHeader(file: java.io.File) {
        // SQLite opens a zero-length file as an empty database (e.g. after a kill during creation).
        if (!file.exists() || file.length() == 0L) return
        val header = ByteArray(16)
        check(file.inputStream().use { it.read(header) } == 16 &&
            header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))) {
            "Database is not readable SQLite plaintext; files preserved: ${file.name}"
        }
    }

    /** Blocking; call from Dispatchers.IO. Inline so suspending work may run while held. */
    internal inline fun <T> exclusive(block: () -> T): T {
        gate.acquire()
        return try { block() } finally { gate.release() }
    }
}

/** Surfaces corruption as an open/query failure instead of the default file deletion. */
private class NonDeletingOpenHelperFactory(
    private val delegate: SupportSQLiteOpenHelper.Factory
) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val callback = configuration.callback
        val preserving = object : SupportSQLiteOpenHelper.Callback(callback.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = callback.onConfigure(db)
            override fun onCreate(db: SupportSQLiteDatabase) = callback.onCreate(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                callback.onUpgrade(db, oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                callback.onDowngrade(db, oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = callback.onOpen(db)
            override fun onCorruption(db: SupportSQLiteDatabase) {
                Log.e("AppDatabaseFactory", "Corruption reported; database files preserved: ${db.path}")
            }
        }
        return delegate.create(SupportSQLiteOpenHelper.Configuration(
            configuration.context, configuration.name, preserving,
            configuration.useNoBackupDirectory, configuration.allowDataLossOnRecovery
        ))
    }
}

internal inline fun <T> AppDatabase.useDatabase(block: (AppDatabase) -> T): T {
    var failure: Throwable? = null
    try {
        return block(this)
    } catch (e: Throwable) {
        failure = e
        throw e
    } finally {
        try { close() } catch (close: Throwable) {
            if (failure == null) throw close
            if (close !== failure) failure.addSuppressed(close)
        }
    }
}
