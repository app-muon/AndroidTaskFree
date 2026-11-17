package com.taskfree.app.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskfree.app.ResetAppStateRule
import com.taskfree.app.data.backup.BackupCrypto
import com.taskfree.app.data.entities.Category
import com.taskfree.app.data.entities.Task
import com.taskfree.app.data.entities.TaskNote
import com.taskfree.app.data.repository.*
import com.taskfree.app.domain.model.Recurrence
import com.taskfree.app.ui.enc.fetchWords
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class RepeatEncryptionTest {
    @get:Rule val reset = ResetAppStateRule()

    @Test fun encryptedVersion20MigrationAndBackupRestoreKeepDataAndIntervals() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val day = LocalDate.of(2026, 10, 6)
        val source = Task(1, 1, "Repeat", due = day, baseDate = day, singleCategoryPageOrder = 0,
            recurrence = Recurrence.MONTHLY, recurrenceInterval = 1,
            originalCreatedAt = Instant.parse("2026-01-01T08:00:00Z"))
        val tasks = listOf(source, source.copy(id = 2, sourceTaskId = 1, text = "Successor"))
        val note = TaskNote(1, 2, "Keep this note", Instant.parse("2026-10-06T08:00:00Z"))
        reset.seed {
            categoryDao().insertAll(listOf(Category(1, "Home", 0)))
            taskDao().insertAll(tasks)
            taskNoteDao().insertAll(listOf(note))
        }
        val phrase = fetchWords().take(8)
        reset.newProcess()
        RealDatabaseMigrator.migrateToEncrypted(context, phrase)
        reset.newProcess()
        val encrypted = reset.db()
        // Reconstruct the exact v20 table shape and force Room's actual encrypted migration path.
        encrypted.openHelper.writableDatabase.execSQL("ALTER TABLE Task DROP COLUMN recurrenceInterval")
        encrypted.openHelper.writableDatabase.execSQL("DELETE FROM room_master_table")
        encrypted.openHelper.writableDatabase.execSQL("PRAGMA user_version = 20")
        reset.newProcess()
        val migrated = reset.db()
        assertEquals(tasks, migrated.taskDao().getAllNow().sortedBy { it.id })
        assertEquals(listOf(note), migrated.taskNoteDao().getAllNow())
        val custom = tasks.map { it.copy(recurrenceInterval = 99) }
        for (task in custom) migrated.taskDao().update(task)
        val json = BackupManager.buildJson(migrated)
        for ((name, bytes) in listOf(
            "repeat.json" to json,
            "repeat.tfbackup" to BackupCrypto.encrypt(json, BackupCrypto.deriveKey(phrase))
        )) {
            val file = File(context.cacheDir, name).apply { writeBytes(bytes) }
            val read = BackupManager.read(context, Uri.fromFile(file))
            val backup = when (read) {
                is BackupSource.Plain -> read.backup
                is BackupSource.Encrypted -> BackupManager.unlock(read, phrase)!!
            }
            BackupManager.restore(backup, TaskRepository(migrated))
            assertEquals(custom, migrated.taskDao().getAllNow().sortedBy { it.id })
            assertEquals(listOf(note), migrated.taskNoteDao().getAllNow())
        }
        reset.newProcess()
        assertEquals(custom, reset.db().taskDao().getAllNow().sortedBy { it.id })
    }
}
