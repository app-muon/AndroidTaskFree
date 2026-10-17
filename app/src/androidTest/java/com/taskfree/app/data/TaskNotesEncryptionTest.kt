package com.taskfree.app.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskfree.app.Prefs
import com.taskfree.app.ResetAppStateRule
import com.taskfree.app.data.backup.BackupCrypto
import com.taskfree.app.data.database.AppDatabase
import com.taskfree.app.data.entities.Category
import com.taskfree.app.data.entities.Task
import com.taskfree.app.data.entities.TaskNote
import com.taskfree.app.data.repository.BackupManager
import com.taskfree.app.data.repository.BackupSource
import com.taskfree.app.data.repository.TaskRepository
import com.taskfree.app.ui.enc.fetchWords
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class TaskNotesEncryptionTest {
    @get:Rule val reset = ResetAppStateRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val phrase = fetchWords().take(8)
    private val note = TaskNote(1, 1, "First line\nhttps://example.com",
        Instant.parse("2026-10-16T09:12:30.123Z"), Instant.parse("2026-10-16T10:12:30.456Z"))

    private fun seed() = reset.seed {
        categoryDao().insertAll(listOf(Category(1, "Home", 0)))
        taskDao().insertAll(listOf(Task(1, 1, "First", singleCategoryPageOrder = 0),
            Task(2, 1, "Second", singleCategoryPageOrder = 1)))
        taskNoteDao().insertAll(listOf(note, note.copy(id = 2, taskId = 2, editedAt = null)))
    }

    @Test fun notesSurviveEncryptionReopenAndEncryptedBackupRestore() = runBlocking {
        seed()
        val expected = reset.seed { taskNoteDao().getAllNow() }
        reset.newProcess()
        RealDatabaseMigrator.migrateToEncrypted(context, phrase)
        reset.newProcess()
        val db = reset.db()
        assertEquals(expected, db.taskNoteDao().getAllNow())
        val file = File(context.cacheDir, "notes-backup-test")
        try {
            file.writeBytes(BackupCrypto.encrypt(BackupManager.buildJson(db),
                BackupCrypto.deriveKey(phrase, iterations = 1_000)))
            val source = BackupManager.read(context, Uri.fromFile(file)) as BackupSource.Encrypted
            val backup = BackupManager.unlock(source, phrase)!!
            db.taskNoteDao().delete(note.id)
            BackupManager.restore(backup, TaskRepository(db))
            assertEquals(expected, db.taskNoteDao().getAllNow())
            reset.newProcess()
            assertEquals(expected, reset.db().taskNoteDao().getAllNow())
        } finally { file.delete() }
    }

    @Test fun omittedOrAlteredNoteFailsCopyVerificationAndRestoresOriginal() = runBlocking {
        seed()
        val expected = reset.seed { taskNoteDao().getAllNow() }
        val corruptions: List<(List<TaskNote>) -> List<TaskNote>> = listOf(
            { it.drop(1) },
            { list -> list.map { if (it.id == 1) it.copy(text = "Altered") else it } },
            { list -> list.map { if (it.id == 1) it.copy(editedAt = null) else it } },
            { list -> list.map { if (it.id == 1) it.copy(createdAt = it.createdAt.plusSeconds(1)) else it } },
            { list -> list.map { if (it.id == 1) it.copy(taskId = 2) else it } },
            { list -> list.map { if (it.id == 1) it.copy(id = 9) else it } }
        )
        for (corrupt in corruptions) {
            reset.newProcess()
            val failure = runCatching {
                RealDatabaseMigrator.migrateToEncrypted(context, phrase) { source, target ->
                    target.categoryDao().insertAll(source.categoryDao().getAllNow())
                    target.taskDao().insertAll(source.taskDao().getAllNow())
                    target.taskNoteDao().insertAll(corrupt(source.taskNoteDao().getAllNow()))
                }
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertFalse(Prefs.isEncrypted(context))
            assertEquals(Prefs.EncryptionOutcome.ROLLED_BACK, Prefs.encryptionOutcome(context))
            reset.newProcess()
            assertEquals(expected, reset.seed { taskNoteDao().getAllNow() })
        }
    }

    @Test fun alteredNoteAfterInstallationFailsReopenVerificationAndRollsBack() = runBlocking {
        seed()
        val expected = reset.seed { taskNoteDao().getAllNow() }
        reset.newProcess()
        val hooks = object : MigrationHooks() {
            override fun reopen(db: AppDatabase) {
                super.reopen(db)
                db.openHelper.writableDatabase.execSQL("UPDATE TaskNote SET text = 'Changed after install' WHERE id = 1")
            }
        }
        val failure = runCatching {
            RealDatabaseMigrator.migrateToEncrypted(context, phrase, hooks)
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertFalse(Prefs.isEncrypted(context))
        reset.newProcess()
        assertEquals(expected, reset.db().taskNoteDao().getAllNow())
    }
}
