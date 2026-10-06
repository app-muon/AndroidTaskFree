// data/EncryptedDatabaseTest.kt
package com.taskfree.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskfree.app.data.entities.Category
import com.taskfree.app.enc.DatabaseKeyManager
import com.taskfree.app.ui.enc.fetchWords
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** SQLCipher is native code, so this needs a real device or emulator. */
@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val key = DatabaseKeyManager.deriveKeyFromPhrase(fetchWords().take(8))
    private val otherKey = DatabaseKeyManager.deriveKeyFromPhrase(fetchWords().takeLast(8))

    @Before
    fun setUp() {
        ctx.deleteDatabase(TEMP_DB)
    }

    @After
    fun tearDown() {
        ctx.deleteDatabase(TEMP_DB)
    }

    private fun writeSecret() = runBlocking {
        val db = AppDatabaseFactory.createTempEncryptedDatabase(ctx, key.copyOf())
        db.categoryDao().insertAll(listOf(Category(id = 1, title = "Secret", color = 0)))
        db.close()
    }

    @Test
    fun dataSurvivesReopeningWithTheSameKey() {
        writeSecret()

        val reopened = AppDatabaseFactory.createTempEncryptedDatabase(ctx, key.copyOf())
        try {
            assertEquals("Secret", runBlocking { reopened.categoryDao().getAllNow() }.single().title)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun aWrongKeyCannotReadTheDatabase() {
        writeSecret()

        val wrong = AppDatabaseFactory.createTempEncryptedDatabase(ctx, otherKey.copyOf())
        try {
            assertThrows(Exception::class.java) {
                runBlocking { wrong.categoryDao().getAllNow() }
            }
        } finally {
            wrong.close()
        }
    }

    @Test
    fun theFileOnDiskIsNotPlainSqlite() {
        writeSecret()

        val header = ByteArray(16).also { buf ->
            ctx.getDatabasePath(TEMP_DB).inputStream().use { it.read(buf) }
        }
        assertFalse(String(header, Charsets.US_ASCII).startsWith("SQLite format 3"))
    }

    private companion object {
        const val TEMP_DB = "checklists_temp.db"
    }
}
