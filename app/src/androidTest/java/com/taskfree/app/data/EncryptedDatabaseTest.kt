// data/EncryptedDatabaseTest.kt
package com.taskfree.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskfree.app.data.AppDatabaseFactory.TEMP_DB_NAME
import com.taskfree.app.data.entities.Category
import com.taskfree.app.data.entities.Task
import com.taskfree.app.enc.DatabaseKeyManager
import com.taskfree.app.ui.enc.fetchWords
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import androidx.test.platform.app.InstrumentationRegistry
import com.taskfree.app.Prefs
import com.taskfree.app.data.repository.BackupManager
import com.taskfree.app.data.repository.CategoryRepository
import com.taskfree.app.data.repository.TaskRepository
import com.taskfree.app.data.preferences.TipPreferences
import com.taskfree.app.ui.onboarding.TipId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assume.assumeTrue
import java.io.File

/** SQLCipher is native code, so this needs a real device or emulator. */
@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val key = DatabaseKeyManager.deriveKeyFromPhrase(fetchWords().take(8))
    private val otherKey = DatabaseKeyManager.deriveKeyFromPhrase(fetchWords().takeLast(8))
    private val secretTask = Task(
        id = 1, categoryId = 1, text = "Secret task", singleCategoryPageOrder = 0,
        originalCreatedAt = Instant.parse("2026-01-02T08:15:30.123Z"),
        occurrenceCreatedAt = Instant.parse("2026-10-05T18:45:12.456Z")
    )

    @Before
    fun setUp() {
        ctx.deleteDatabase(TEMP_DB_NAME)
    }

    @After
    fun tearDown() {
        ctx.deleteDatabase(TEMP_DB_NAME)
    }

    private fun writeSecret() = runBlocking {
        val db = AppDatabaseFactory.createTempEncryptedDatabase(ctx, key.copyOf())
        db.categoryDao().insertAll(listOf(Category(id = 1, title = "Secret", color = 0)))
        db.taskDao().insertAll(listOf(secretTask))
        db.close()
    }

    @Test
    fun dataSurvivesReopeningWithTheSameKey() {
        writeSecret()

        val reopened = AppDatabaseFactory.createTempEncryptedDatabase(ctx, key.copyOf())
        try {
            assertEquals("Secret", runBlocking { reopened.categoryDao().getAllNow() }.single().title)
            assertEquals(secretTask, runBlocking { reopened.taskDao().getAllNow() }.single())
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
    fun aWrongKeyLeavesTheEncryptedDatabaseInPlace() {
        writeSecret()

        val wrong = AppDatabaseFactory.createTempEncryptedDatabase(ctx, otherKey.copyOf())
        try {
            assertThrows(Exception::class.java) { wrong.openHelper.writableDatabase }
        } finally {
            wrong.close()
        }
        val reopened = AppDatabaseFactory.createTempEncryptedDatabase(ctx, key.copyOf())
        try {
            assertEquals(secretTask, runBlocking { reopened.taskDao().getAllNow() }.single())
        } finally {
            reopened.close()
        }
    }

    @Test
    fun aCorruptPlaintextDatabaseIsPreservedWhenOpeningFails() {
        val file = ctx.getDatabasePath(TEMP_DB_NAME).apply { parentFile?.mkdirs() }
        // A valid magic string followed by an impossible header is reported as corruption on open.
        val bytes = ByteArray(4096) { 0x5A }
        "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII).copyInto(bytes)
        file.writeBytes(bytes)

        assertThrows(Exception::class.java) {
            AppDatabaseFactory.buildInternal(ctx, TEMP_DB_NAME).useDatabase { it.openHelper.writableDatabase }
        }
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun theFileOnDiskIsNotPlainSqlite() {
        writeSecret()

        val header = ByteArray(16).also { buf ->
            ctx.getDatabasePath(TEMP_DB_NAME).inputStream().use { it.read(buf) }
        }
        assertFalse(String(header, Charsets.US_ASCII).startsWith("SQLite format 3"))
    }

    /** Opt-in fixture for external ADB checks; intentionally retains data after the runner exits. */
    @Test
    fun externalProcessFixture() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val action = args.getString("externalEncryptionFixture")
        assumeTrue(action in listOf("prepare", "verify"))
        RealDatabaseMigrator.resetProcessForTesting()
        val snapshot = File(ctx.externalCacheDir, "encryption-process-before.json")
        if (action == "prepare") {
            RealDatabaseMigrator.resetEncryption(ctx)
            com.taskfree.app.notifications.NotificationScheduler.cancel(ctx, 1, null)
            ctx.getSystemService(android.app.NotificationManager::class.java).cancel(1)
            val count = args.getString("fixtureTasks")?.toInt() ?: 20_000
            // Optional: a future reminder for reboot checks (default stays one second in the past).
            val reminderAt = System.currentTimeMillis() +
                (args.getString("reminderSeconds")?.toLong()?.times(1_000) ?: -1_000)
            AppDatabaseFactory.buildInternal(ctx, AppDatabaseFactory.DB_NAME).useDatabase { db ->
                db.categoryDao().insertAll(listOf(Category(id = 1, title = "Process checks", color = 0xFF336699)))
                db.taskDao().insertAll((1..count).map { id ->
                    Task(id = id, categoryId = 1, text = "Process task $id", singleCategoryPageOrder = id - 1,
                        allCategoryPageOrder = id - 1, sourceTaskId = if (id > 1) id - 1 else null,
                        originalCreatedAt = Instant.parse("2025-07-15T12:00:00Z"),
                        occurrenceCreatedAt = Instant.parse("2026-10-06T08:00:00Z"),
                        reminderTime = if (id == 1) Instant.ofEpochMilli(reminderAt) else null)
                })
                TaskRepository(db).reindexAllTaskPageOrders()
                snapshot.writeBytes(BackupManager.buildJson(CategoryRepository(db), TaskRepository(db)))
            }
            Prefs.savePhrase(ctx, fetchWords().take(8))
            val tips = TipPreferences(ctx)
            TipId.entries.forEach { tips.markSeen(it) }
            if (args.getString("pending") == "true") Prefs.requestEncryption(ctx, fetchWords().take(8))
        } else {
            RealDatabaseMigrator.start(ctx).join()
            val db = AppDatabaseFactory.getDatabase(ctx)
            val before = Json.parseToJsonElement(snapshot.readText()).jsonObject
            val after = Json.parseToJsonElement(BackupManager.buildJson(CategoryRepository(db), TaskRepository(db))
                .decodeToString()).jsonObject
            assertEquals(before["tasks"], after["tasks"])
            assertEquals(before["categories"], after["categories"])
        }
    }

}
