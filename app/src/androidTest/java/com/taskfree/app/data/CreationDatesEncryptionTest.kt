package com.taskfree.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskfree.app.ResetAppStateRule
import com.taskfree.app.data.entities.Category
import com.taskfree.app.data.entities.Task
import com.taskfree.app.data.repository.BackupManager
import com.taskfree.app.data.repository.CategoryRepository
import com.taskfree.app.data.repository.TaskRepository
import com.taskfree.app.ui.enc.fetchWords
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** Exercises the app's actual plaintext-to-SQLCipher copy, including backup output. */
@RunWith(AndroidJUnit4::class)
class CreationDatesEncryptionTest {
    @get:Rule
    val reset = ResetAppStateRule()

    @Test
    fun encryptionCopyPreservesCreationDatesAndBackupValues() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expected = Task(
            id = 1, categoryId = 1, text = "Water plants", singleCategoryPageOrder = 0,
            originalCreatedAt = Instant.parse("2025-07-15T12:00:00Z"),
            occurrenceCreatedAt = Instant.parse("2026-10-06T08:00:00.123Z")
        )
        reset.seed {
            categoryDao().insertAll(listOf(Category(id = 1, title = "Home", color = 0)))
            taskDao().insertAll(listOf(expected.copy(id = 3, sourceTaskId = 1), expected, expected.copy(
                id = 2, text = "Legacy task", originalCreatedAt = null, occurrenceCreatedAt = null
            )))
        }
        val before = BackupManager.buildJson(CategoryRepository(reset.db()), TaskRepository(reset.db()))

        reset.newProcess()
        RealDatabaseMigrator.migrateToEncrypted(context, fetchWords().take(8))
        reset.newProcess()

        val encrypted = reset.db()
        assertEquals(expected, encrypted.taskDao().taskById(expected.id))
        assertEquals(1, encrypted.taskDao().taskById(3)!!.sourceTaskId)
        val after = BackupManager.buildJson(CategoryRepository(encrypted), TaskRepository(encrypted))
        assertEquals(
            Json.parseToJsonElement(before.decodeToString()).jsonObject["tasks"],
            Json.parseToJsonElement(after.decodeToString()).jsonObject["tasks"]
        )
    }
}
