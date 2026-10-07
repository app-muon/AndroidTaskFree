package com.taskfree.app.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskfree.app.Prefs
import com.taskfree.app.ResetAppStateRule
import com.taskfree.app.data.AppDatabaseFactory.TEMP_DB_NAME
import com.taskfree.app.data.database.AppDatabase
import com.taskfree.app.data.entities.Category
import com.taskfree.app.data.entities.Task
import com.taskfree.app.domain.model.Recurrence
import com.taskfree.app.domain.model.TaskStatus
import com.taskfree.app.enc.DatabaseKeyManager
import com.taskfree.app.ui.enc.fetchWords
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class EncryptionRetryTest {
    @get:Rule
    val reset = ResetAppStateRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val phrase = fetchWords().take(8)
    private val otherPhrase = fetchWords().takeLast(8)
    private val categories = listOf(
        Category(id = 1, title = "Home", color = 0xFF336699, categoryPageOrder = 4),
        Category(id = 2, title = "Old category", color = 0xFF123456, isDeleted = true)
    )
    private val parent = Task(
        id = 1, categoryId = 1, text = "Water plants", singleCategoryPageOrder = 4,
        allCategoryPageOrder = 8, status = TaskStatus.DONE,
        due = LocalDate.of(2026, 10, 6), baseDate = LocalDate.of(2026, 10, 6),
        completedDate = LocalDate.of(2026, 10, 6), recurrence = Recurrence.DAILY,
        reminderTime = Instant.parse("2026-10-06T08:00:00Z"),
        originalCreatedAt = Instant.parse("2025-07-15T12:00:00.123Z"),
        occurrenceCreatedAt = Instant.parse("2026-10-06T08:00:00.456Z")
    )
    private val tasks = listOf(
        parent,
        parent.copy(
            id = 2, text = "Edited successor", sourceTaskId = 1,
            status = TaskStatus.IN_PROGRESS, completedDate = null,
            due = LocalDate.of(2026, 10, 7), baseDate = LocalDate.of(2026, 10, 7),
            reminderTime = Instant.parse("2026-10-07T08:00:00Z"),
            singleCategoryPageOrder = 7, allCategoryPageOrder = 13,
            occurrenceCreatedAt = Instant.parse("2026-10-06T09:00:00.789Z")
        ),
        Task(id = 3, categoryId = 2, text = "Archived legacy task",
            singleCategoryPageOrder = 9, allCategoryPageOrder = 15, isArchived = true)
    )

    @Test
    fun staleTemporaryDatabaseWithSameKeyIsDiscarded() = runBlocking {
        staleDatabaseIsDiscarded(phrase)
    }

    @Test
    fun staleTemporaryDatabaseWithDifferentKeyIsDiscarded() = runBlocking {
        staleDatabaseIsDiscarded(otherPhrase)
    }

    private suspend fun staleDatabaseIsDiscarded(stalePhrase: List<String>) {
        seedSource()
        val stale = AppDatabaseFactory.createTempEncryptedDatabase(
            context, DatabaseKeyManager.deriveKeyFromPhrase(stalePhrase)
        )
        try {
            stale.categoryDao().insertAll(listOf(Category(id = 90, title = "Stale", color = 0)))
            stale.taskDao().insertAll(listOf(parent.copy(id = 90, categoryId = 90)))
        } finally {
            stale.close()
        }
        // Leftover SQLite sidecars must be removed along with the stale database.
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            File(context.getDatabasePath(TEMP_DB_NAME).path + suffix).writeText("stale")
        }

        RealDatabaseMigrator.migrateToEncrypted(context, phrase)

        assertEncryptedContents(phrase)
    }

    @Test
    fun partialCopyFailureCanRetryWithSamePhrase() = runBlocking {
        partialCopyThenRetry(phrase)
    }

    @Test
    fun partialCopyFailureCanRetryWithDifferentPhrase() = runBlocking {
        partialCopyThenRetry(otherPhrase)
    }

    private suspend fun partialCopyThenRetry(retryPhrase: List<String>) {
        seedSource()
        val failure = IOException("Injected failure after a partial copy")
        val thrown = runCatching {
            RealDatabaseMigrator.migrateToEncrypted(context, phrase) { source, target ->
                target.categoryDao().insertAll(source.categoryDao().getAllNow())
                target.taskDao().insertAll(listOf(source.taskDao().getAllNow().first()))
                assertEquals(1, target.taskDao().getAllNow().size)
                throw failure
            }
        }.exceptionOrNull()

        assertSame(failure, thrown)
        assertEquals("Migration failed: ${failure.message}", RealDatabaseMigrator.error.value)
        assertTemporaryFilesRemoved()
        assertFalse(Prefs.isEncrypted(context))
        assertNull(DatabaseKeyManager.getCachedKey())
        // No reset or explicit factory clearing: failure must release its cached plaintext DB.
        assertContents(reset.db())

        RealDatabaseMigrator.migrateToEncrypted(context, retryPhrase)

        assertEncryptedContents(retryPhrase)
    }

    @Test
    fun cleanupFailurePreservesCopyErrorAndStillResetsEncryptionState() = runBlocking {
        seedSource()
        var deletions = 0
        val cleanupFailure = IOException("Injected cleanup failure")
        val failingContext = object : ContextWrapper(context) {
            override fun deleteDatabase(name: String): Boolean {
                if (name == TEMP_DB_NAME && ++deletions > 1) throw cleanupFailure
                return super.deleteDatabase(name)
            }
        }
        val copyFailure = IOException("Injected copy failure")
        val thrown = runCatching {
            RealDatabaseMigrator.migrateToEncrypted(failingContext, phrase) { source, target ->
                target.categoryDao().insertAll(source.categoryDao().getAllNow())
                // Make the reset observable even when file cleanup throws.
                Prefs.setEncrypted(context, true)
                DatabaseKeyManager.cacheKey(byteArrayOf(1, 2, 3))
                throw copyFailure
            }
        }.exceptionOrNull()

        assertSame(copyFailure, thrown)
        assertTrue(copyFailure.suppressed.contains(cleanupFailure))
        assertFalse(Prefs.isEncrypted(context))
        assertNull(DatabaseKeyManager.getCachedKey())
        assertContents(reset.db())

        RealDatabaseMigrator.migrateToEncrypted(context, otherPhrase)
        assertEncryptedContents(otherPhrase)
    }

    @Test
    fun remainingTemporaryFilesAbortBeforeCopying() = runBlocking {
        seedSource()
        val tempFile = context.getDatabasePath(TEMP_DB_NAME)
        tempFile.writeText("leftover database")
        val refusingContext = object : ContextWrapper(context) {
            override fun deleteDatabase(name: String): Boolean =
                if (name == TEMP_DB_NAME) false else super.deleteDatabase(name)
        }
        var copied = false
        val thrown = runCatching {
            RealDatabaseMigrator.migrateToEncrypted(refusingContext, phrase) { _, _ -> copied = true }
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertFalse(copied)
        assertEquals("leftover database", tempFile.readText())
        assertFalse(Prefs.isEncrypted(context))
        assertNull(DatabaseKeyManager.getCachedKey())
        assertContents(reset.db())

        RealDatabaseMigrator.migrateToEncrypted(context, phrase)
        assertEncryptedContents(phrase)
    }

    private fun seedSource() = reset.seed {
        categoryDao().insertAll(categories)
        taskDao().insertAll(tasks)
    }

    private suspend fun assertContents(db: AppDatabase) {
        assertEquals(categories, db.categoryDao().getAllNow().sortedBy { it.id })
        assertEquals(tasks, db.taskDao().getAllNow().sortedBy { it.id })
    }

    private suspend fun assertEncryptedContents(expectedPhrase: List<String>) {
        assertTemporaryFilesRemoved()
        assertTrue(Prefs.isEncrypted(context))
        assertNull(RealDatabaseMigrator.error.value)
        assertEquals(100, RealDatabaseMigrator.progress.value)
        assertArrayEquals(
            DatabaseKeyManager.deriveKeyFromPhrase(expectedPhrase),
            DatabaseKeyManager.loadDerivedKey(context)
        )
        AppDatabaseFactory.clearInstance()
        DatabaseKeyManager.clearCachedKey()
        assertContents(reset.db())
    }

    private fun assertTemporaryFilesRemoved() {
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            val file = File(context.getDatabasePath(TEMP_DB_NAME).path + suffix)
            assertFalse("Temporary file remains: ${file.name}", file.exists())
        }
    }
}
