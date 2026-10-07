package com.taskfree.app.data.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskfree.app.data.entities.Category
import com.taskfree.app.data.entities.Task
import com.taskfree.app.data.repository.TaskRepository
import com.taskfree.app.domain.model.Recurrence
import com.taskfree.app.domain.model.TaskInput
import com.taskfree.app.domain.model.TaskStatus
import com.taskfree.app.testutil.datesAt
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class CreationDatesMigrationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `version 17 preserves tasks and unknown creation dates`() = runTest { assertUpgrade(17) }

    @Test
    fun `version 18 preserves recorded creation dates`() = runTest { assertUpgrade(18) }

    private suspend fun assertUpgrade(fromVersion: Int) {
        val file = tmp.newFile("version17.db")
        val today = LocalDate.of(2026, 10, 6)
        val reminder = Instant.parse("2026-10-06T08:00:00Z")
        val recordedCreation = if (fromVersion == 18) reminder.minusSeconds(86400) else null

        // A real v17 schema fixture: Room validates the migrated tables when it opens.
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("""
                CREATE TABLE Category (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    title TEXT NOT NULL,
                    color INTEGER NOT NULL,
                    categoryPageOrder INTEGER NOT NULL,
                    isDeleted INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            old.execSQL("""
                CREATE TABLE Task (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    categoryId INTEGER NOT NULL,
                    text TEXT NOT NULL,
                    due INTEGER,
                    baseDate INTEGER,
                    singleCategoryPageOrder INTEGER NOT NULL,
                    allCategoryPageOrder INTEGER NOT NULL,
                    completedDate INTEGER,
                    recurrence TEXT NOT NULL DEFAULT 'NONE',
                    status TEXT NOT NULL DEFAULT 'TODO',
                    isArchived INTEGER NOT NULL DEFAULT 0,
                    reminderTime INTEGER,
                    FOREIGN KEY(categoryId) REFERENCES Category(id) ON DELETE CASCADE
                )
            """.trimIndent())
            old.execSQL("CREATE INDEX index_Task_categoryId ON Task(categoryId)")
            old.execSQL("INSERT INTO Category VALUES (1, 'Home', 123, 2, 0)")
            old.execSQL("INSERT INTO Category VALUES (2, 'Deleted', 456, 3, 1)")
            old.execSQL(
                "INSERT INTO Task VALUES (7, 1, 'Water plants', ?, ?, 4, 5, NULL, 'DAILY', 'TODO', 0, ?)",
                arrayOf(today.toEpochDay(), today.toEpochDay(), reminder.toEpochMilli())
            )
            old.execSQL(
                "INSERT INTO Task VALUES (8, 2, 'Old task', NULL, NULL, 6, 7, ?, 'NONE', 'DONE', 1, NULL)",
                arrayOf(today.minusDays(1).toEpochDay())
            )
            if (fromVersion == 18) {
                old.execSQL("ALTER TABLE Task ADD COLUMN originalCreatedAt INTEGER")
                old.execSQL("ALTER TABLE Task ADD COLUMN occurrenceCreatedAt INTEGER")
                old.execSQL("UPDATE Task SET originalCreatedAt = ?, occurrenceCreatedAt = ? WHERE id = 7",
                    arrayOf(recordedCreation!!.toEpochMilli(), recordedCreation.toEpochMilli()))
            }
            old.version = fromVersion
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath)
            .addMigrations(MIGRATION_17_18, MIGRATION_18_19)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(
                listOf(Category(1, "Home", 123, 2), Category(2, "Deleted", 456, 3, true)),
                migrated.categoryDao().getAllNow().sortedBy { it.id }
            )
            assertEquals(
                listOf(
                    Task(
                        id = 7, categoryId = 1, text = "Water plants", due = today, baseDate = today,
                        singleCategoryPageOrder = 4, allCategoryPageOrder = 5,
                        recurrence = Recurrence.DAILY, reminderTime = reminder,
                        originalCreatedAt = recordedCreation, occurrenceCreatedAt = recordedCreation
                    ),
                    Task(
                        id = 8, categoryId = 2, text = "Old task", singleCategoryPageOrder = 6,
                        allCategoryPageOrder = 7, completedDate = today.minusDays(1),
                        status = TaskStatus.DONE, isArchived = true
                    )
                ),
                migrated.taskDao().getAllNow().sortedBy { it.id }
            )
            assertEquals(19, migrated.openHelper.readableDatabase.version)

            val repo = TaskRepository(migrated, datesAt(today))
            val newId = repo.createTask(TaskInput("New task", null, Recurrence.NONE, 1))
            val added = repo.taskById(newId)!!
            assertNotNull(added.originalCreatedAt)
            assertEquals(added.originalCreatedAt, added.occurrenceCreatedAt)
            val nextId = repo.updateTaskStatus(7, TaskStatus.DONE).nextCreatedId!!
            assertEquals(7, repo.taskById(nextId)!!.sourceTaskId)
            repo.deleteTaskPermanently(repo.taskById(7)!!)
            assertEquals(null, repo.taskById(nextId)!!.sourceTaskId)
        } finally {
            migrated.close()
        }
    }
}
