package com.taskfree.app.data

import android.content.Context
import android.util.Log
import com.taskfree.app.Prefs
import com.taskfree.app.data.database.AppDatabase
import com.taskfree.app.enc.DatabaseKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

object RealDatabaseMigrator {

    private const val DB_NAME = "checklists.db"

    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    suspend fun migrateToEncrypted(context: Context, phrase: List<String>) {
        migrateToEncrypted(context, phrase) { source, target ->
            target.categoryDao().insertAll(source.categoryDao().getAllNow())
            target.taskDao().insertAll(source.taskDao().getAllNow())
        }
    }

    internal suspend fun migrateToEncrypted(
        context: Context,
        phrase: List<String>,
        copyTables: suspend (source: AppDatabase, target: AppDatabase) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            try {
                _error.value = null
                _progress.value = 0

                Log.d("RealDatabaseMigrator", "Starting database encryption migration")

                // Step 1: Derive encryption key from phrase
                _progress.value = 10
                val keyBytes: ByteArray = DatabaseKeyManager.deriveKeyFromPhrase(phrase)
                Log.d("RealDatabaseMigrator", "Encryption key derived")

                // Step 2: Close existing database connections
                _progress.value = 20
                AppDatabaseFactory.clearInstance()
                Log.d("RealDatabaseMigrator", "Database connections closed")

                // Step 3: Get database file path
                _progress.value = 30
                val dbFile = context.getDatabasePath(DB_NAME)

                // Step 4: Encrypt database or create new encrypted one
                _progress.value = 40
                deleteTemporaryDatabase(context)
                if (dbFile.exists()) {
                    Log.d("RealDatabaseMigrator", "Existing database found, encrypting in-place")
                    encryptByCopy(context, keyBytes, dbFile, copyTables)
                } else {
                    Log.d(
                        "RealDatabaseMigrator",
                        "No existing database, will create new encrypted database"
                    )
                }
                _progress.value = 80

                // Step 5: Store encryption key and mark as encrypted (ONLY after success)
                _progress.value = 90
                DatabaseKeyManager.storeKey(context, keyBytes)
                DatabaseKeyManager.cacheKey(keyBytes)
                Prefs.setEncrypted(context, true)

                _progress.value = 100
                Log.d(
                    "RealDatabaseMigrator", "Database encryption migration completed successfully"
                )

            } catch (e: Exception) {
                Log.e("RealDatabaseMigrator", "Migration failed", e)
                _error.value = "Migration failed: ${e.message}"

                // Cleanup on failure
                try {
                    cleanupFailedMigration(context)
                } catch (cleanupError: Exception) {
                    e.addSuppressed(cleanupError)
                    Log.e("RealDatabaseMigrator", "Cleanup failed", cleanupError)
                }
                throw e
            }
        }
    }

    private suspend fun encryptByCopy(
        context: Context,
        key: ByteArray,
        srcFile: File,
        copyTables: suspend (source: AppDatabase, target: AppDatabase) -> Unit
    ) {
        // 1. build temp encrypted Room DB
        val tmpDb = AppDatabaseFactory.createTempEncryptedDatabase(context, key)

        try {
            // 2. open the original DB through Room (plain)
            Prefs.setEncrypted(context, false)
            val plainDb = AppDatabaseFactory.getDatabase(context)
            try {
                // 3. copy tables
                copyTables(plainDb, tmpDb)
            } finally {
                AppDatabaseFactory.clearInstance()
            }
        } finally {
            tmpDb.close()
        }

        // 4. swap files (db + sidecars)
        val tmpFile = context.getDatabasePath(AppDatabaseFactory.TEMP_DB_NAME)
        val bakFile = File(srcFile.parent, "checklists_backup.db")
        val srcWal = File(srcFile.path + "-wal")
        val srcShm = File(srcFile.path + "-shm")
        val tmpWal = File(tmpFile.path + "-wal")
        val tmpShm = File(tmpFile.path + "-shm")

        if (srcFile.exists()) {
               srcWal.delete(); srcShm.delete()
               srcFile.renameTo(bakFile)
             }
        check(tmpFile.renameTo(srcFile)) { "rename failed" }
        if (tmpWal.exists()) tmpWal.renameTo(File(srcFile.path + "-wal"))
        if (tmpShm.exists()) tmpShm.renameTo(File(srcFile.path + "-shm"))
        bakFile.delete()
    }

    private fun deleteTemporaryDatabase(context: Context) {
        context.deleteDatabase(AppDatabaseFactory.TEMP_DB_NAME)
        val tempFile = context.getDatabasePath(AppDatabaseFactory.TEMP_DB_NAME)
        check(listOf("", "-wal", "-shm", "-journal").none { File(tempFile.path + it).exists() }) {
            "Temporary encryption database files could not be deleted"
        }
    }

    private fun cleanupFailedMigration(context: Context) {
        try {
            deleteTemporaryDatabase(context)
            val dbFile = context.getDatabasePath(DB_NAME)
            File(File(dbFile.parent, "checklists_backup.db").path + "-wal").delete()
            File(File(dbFile.parent, "checklists_backup.db").path + "-shm").delete()
        } finally {
            // Clear encryption state
            DatabaseKeyManager.clearCachedKey()
            Prefs.setEncrypted(context, false)
        }
        Log.d("RealDatabaseMigrator", "Failed migration cleanup completed")
    }
}
