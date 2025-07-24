package com.taskfree.app.data

import android.content.Context
import android.util.Log
import com.taskfree.app.Prefs
import com.taskfree.app.enc.DatabaseKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import net.sqlcipher.database.SQLiteDatabase
import java.io.File

object RealDatabaseMigrator {

    private const val DB_NAME = "checklists.db"

    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    suspend fun migrateToEncrypted(context: Context, phrase: List<String>) {
        withContext(Dispatchers.IO) {
            SQLiteDatabase.loadLibs(context)
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
                if (dbFile.exists()) {
                    Log.d("RealDatabaseMigrator", "Existing database found, encrypting in-place")
                    encryptByCopy(context, keyBytes, dbFile)
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
                cleanupFailedMigration(context)
                throw e
            }
        }
    }

    private suspend fun encryptByCopy(
        context: Context, key: ByteArray, srcFile: File
    ) {
        // 1. build temp encrypted Room DB
        val tmpDb = AppDatabaseFactory.createTempEncryptedDatabase(context, key)

        // 2. open the original DB through Room (plain)
        Prefs.setEncrypted(context, false)            // ensure plain open
        val plainDb = AppDatabaseFactory.getDatabase(context)

        try {
            // 3. copy tables
            tmpDb.categoryDao().insertAll(plainDb.categoryDao().getAllNow())
            tmpDb.taskDao().insertAll(plainDb.taskDao().getAllNow())
        } finally {
            plainDb.close()
            tmpDb.close()
        }

        // 4. swap files
        val tmpFile = File(srcFile.parent, "checklists_temp.db")
        val bakFile = File(srcFile.parent, "checklists_backup.db")

        if (srcFile.exists()) srcFile.renameTo(bakFile)
        check(tmpFile.renameTo(srcFile)) { "rename failed" }
        bakFile.delete()                              // success – clean up
    }

    private fun cleanupFailedMigration(context: Context) {
        try {
            val dbFile = context.getDatabasePath(DB_NAME)
            val encryptedPath = "${dbFile.absolutePath}.encrypted"

            // Remove any partial encrypted file
            File(encryptedPath).delete()

            // Clear encryption state
            DatabaseKeyManager.clearCachedKey()
            Prefs.setEncrypted(context, false)

            Log.d("RealDatabaseMigrator", "Failed migration cleanup completed")

        } catch (e: Exception) {
            Log.e("RealDatabaseMigrator", "Cleanup failed", e)
        }
    }
}