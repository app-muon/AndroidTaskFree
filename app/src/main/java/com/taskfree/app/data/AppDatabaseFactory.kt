package com.taskfree.app.data

import android.content.Context
import android.util.Log
import androidx.room.Room
import com.taskfree.app.Prefs
import com.taskfree.app.data.database.AppDatabase
import com.taskfree.app.enc.DatabaseKeyManager
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

object AppDatabaseFactory {
    @Volatile
    private var INSTANCE: AppDatabase? = null

    fun getDatabase(context: Context): AppDatabase {
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
        ).openHelperFactory(SupportOpenHelperFactory(key)).build()
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

            builder.openHelperFactory(SupportOpenHelperFactory(key.copyOf()))
        }

        return builder.build()
    }

    private fun verifyDatabaseKey(context: Context, key: ByteArray): Boolean {
        val dbName = "checklists.db"
        if (!context.getDatabasePath(dbName).exists()) return true
        return try {
            val tmp = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
                .openHelperFactory(SupportOpenHelperFactory(key))
                .build()
            tmp.openHelper.readableDatabase.query("SELECT 1").use { /* ok */ }
            tmp.close()
            true
        } catch (t: Throwable) { false }
    }

    fun clearInstance() {
        synchronized(this) {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}