package com.taskfree.app.data

import android.content.Context
import android.util.Log
import androidx.room.Room
import com.taskfree.app.Prefs
import com.taskfree.app.data.database.AppDatabase
import com.taskfree.app.enc.DatabaseKeyManager
import net.sqlcipher.database.SupportFactory

object AppDatabaseFactory {
    @Volatile
    private var INSTANCE: AppDatabase? = null

    fun getDatabase(context: Context): AppDatabase {
        net.sqlcipher.database.SQLiteDatabase.loadLibs(context)
        return INSTANCE ?: synchronized(this) {
            try {
                INSTANCE?.close()
                INSTANCE = null

                val instance = buildDatabase(context)
                INSTANCE = instance
                instance
            } catch (e: Exception) {
                Log.e("AppDatabaseFactory", "Failed to open database", e)
                // Clear any potentially corrupted instance
                INSTANCE?.close()
                INSTANCE = null
                throw e
            }
        }
    }

    fun createTempEncryptedDatabase(context: Context, key: ByteArray): AppDatabase {
        return Room.databaseBuilder(
            context.applicationContext, AppDatabase::class.java, "checklists_temp.db"
        ).openHelperFactory(SupportFactory(key, null, false)).build()
    }

    private fun buildDatabase(context: Context): AppDatabase {
        val builder = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "checklists.db"
        )

        if (Prefs.isEncrypted(context)) {
            // LOG THE STORED VALUES
            Prefs.logSavedPassphraseAndKey(context)

            var key = DatabaseKeyManager.getCachedKey()
            if (key == null) {
                key = DatabaseKeyManager.loadDerivedKey(context)
                if (key != null) {
                    DatabaseKeyManager.cacheKey(key)
                }
            }

            if (key == null) {
                throw IllegalStateException("No encryption key available")
            }

            // LOG THE CURRENT KEY BEING USED
            DatabaseKeyManager.logCurrentKey()

            // Test key derivation from stored phrase
            val storedPhrase = Prefs.loadPhrase(context)
            if (storedPhrase != null) {
                val derivedFromPhrase = DatabaseKeyManager.deriveKeyFromPhrase(storedPhrase)
                val derivedBase64 = android.util.Base64.encodeToString(derivedFromPhrase, android.util.Base64.NO_WRAP)
                Log.d("DatabaseFactory", "Key derived from stored phrase: $derivedBase64")

                // Compare with stored key
                val storedKey = Prefs.loadDerivedKey(context)
                if (storedKey != null) {
                    val matches = derivedFromPhrase.contentEquals(storedKey)
                    Log.d("DatabaseFactory", "Derived key matches stored key: $matches")
                }
            }

            builder.openHelperFactory(SupportFactory(key.copyOf(), null, false))
        }

        return builder.build()
    }

    private fun createFreshSupportFactory(key: ByteArray): SupportFactory {
        // Create a wrapper that ensures fresh factory instances
        return object : SupportFactory(key.copyOf(), null, false) {
            // This ensures each connection gets a fresh factory state
        }
    }

    private fun verifyDatabaseKey(context: Context, key: ByteArray): Boolean {
        val dbFile = context.getDatabasePath("checklists.db")
        if (!dbFile.exists()) {
            return true // New database, key is fine
        }

        return try {
            // Use SQLCipher's database opening method
            val keyString = String(key, Charsets.ISO_8859_1)
            val testDb = net.sqlcipher.database.SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                keyString,
                null,
                net.sqlcipher.database.SQLiteDatabase.OPEN_READONLY
            )

            // Try to execute a simple query to verify the key works
            val cursor = testDb.rawQuery("SELECT count(*) FROM sqlite_master", null)
            cursor.moveToFirst()
            cursor.close()
            testDb.close()

            Log.d("DatabaseFactory", "Key verification successful")
            true
        } catch (e: Exception) {
            Log.e("DatabaseFactory", "Key verification failed", e)
            false
        }
    }
    fun clearInstance() {
        synchronized(this) {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}