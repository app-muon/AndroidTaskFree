// data/repository/BackupManagerTest.kt
package com.taskfree.app.data.repository

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskfree.app.BuildConfig
import com.taskfree.app.R
import com.taskfree.app.data.database.AppDatabase
import com.taskfree.app.data.entities.Category
import com.taskfree.app.data.entities.Task
import com.taskfree.app.data.repository.BackupManager.BackupValidationException
import com.taskfree.app.domain.model.Recurrence
import com.taskfree.app.domain.model.TaskStatus
import com.taskfree.app.testutil.assertFails
import com.taskfree.app.testutil.datesAt
import com.taskfree.app.testutil.inMemoryDb
import com.taskfree.app.testutil.insertCategory
import com.taskfree.app.testutil.insertTask
import com.taskfree.app.testutil.task
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class BackupManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val today = LocalDate.of(2026, 10, 6)
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val json = Json { encodeDefaults = true }

    private lateinit var db: AppDatabase
    private lateinit var taskRepo: TaskRepository
    private lateinit var catRepo: CategoryRepository

    @Before
    fun setUp() {
        db = inMemoryDb()
        taskRepo = TaskRepository(db, datesAt(today))
        catRepo = CategoryRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private fun uriFor(bytes: ByteArray): Uri =
        Uri.fromFile(tmp.newFile().apply { writeBytes(bytes) })

    private fun uriFor(backup: Backup): Uri =
        uriFor(json.encodeToString(Backup.serializer(), backup).toByteArray())

    private fun backup(categories: List<Category>, tasks: List<Task>, version: String = "1.0") =
        Backup(version = version, app_version = "test", exported_at = "now", categories = categories, tasks = tasks)

    private val home = Category(id = 1, title = "Home", color = 0xFF3F51B5)

    private suspend fun seed() {
        val home = db.insertCategory("Home", order = 0)
        val gone = db.insertCategory("Gone", order = 1, isDeleted = true)
        db.insertTask(
            task(
                home, "Water plants", due = today, recurrence = Recurrence.WEEKLY,
                reminderTime = Instant.parse("2026-10-06T08:00:00Z"), singleOrder = 0, allOrder = 1
            )
        )
        db.insertTask(
            task(
                home, "Done thing", status = TaskStatus.DONE, completedDate = today.minusDays(2),
                singleOrder = 1, allOrder = 0
            )
        )
        db.insertTask(task(gone, "Archived orphan", isArchived = true))
    }

    @Test
    fun `export then import restores identical data`() = runTest {
        seed()
        val bytes = BackupManager.buildJson(catRepo, taskRepo)
        val expectedCats = catRepo.snapshot().sortedBy { it.id }
        val expectedTasks = taskRepo.snapshot().sortedBy { it.id }

        val target = inMemoryDb()
        try {
            BackupManager.import(ctx, uriFor(bytes), TaskRepository(target, datesAt(today)))

            assertEquals(expectedCats, target.categoryDao().getAllNow().sortedBy { it.id })
            assertEquals(expectedTasks, target.taskDao().getAllNow().sortedBy { it.id })
        } finally {
            target.close()
        }
    }

    @Test
    fun `export format has metadata and omits isDeleted for live categories`() = runTest {
        seed()
        val root = Json.parseToJsonElement(BackupManager.buildJson(catRepo, taskRepo).decodeToString()).jsonObject

        assertEquals("1.0", root["version"]!!.jsonPrimitive.content)
        assertEquals(BuildConfig.VERSION_NAME, root["app_version"]!!.jsonPrimitive.content)
        val cats = root["categories"]!!.jsonArray.map { it.jsonObject }
        assertFalse("isDeleted" in cats.single { it["title"]!!.jsonPrimitive.content == "Home" })
        assertEquals("true", cats.single { it["title"]!!.jsonPrimitive.content == "Gone" }["isDeleted"]!!.jsonPrimitive.content)
        val task = root["tasks"]!!.jsonArray.first().jsonObject
        assertEquals("2026-10-06", task["due"]!!.jsonPrimitive.content)
    }

    @Test
    fun `older backups without isDeleted import and ignore unknown keys`() = runTest {
        val bytes = javaClass.getResourceAsStream("/backups/v1_0_no_isDeleted.json")!!.readBytes()

        BackupManager.import(ctx, uriFor(bytes), taskRepo)

        val cat = catRepo.snapshot().single()
        assertEquals("Home", cat.title)
        assertFalse(cat.isDeleted)
        val tasks = taskRepo.snapshot().sortedBy { it.id }
        assertEquals(listOf("Fix tap", "Bins"), tasks.map { it.text })
        assertEquals(Instant.parse("2026-01-20T09:00:00Z"), tasks[0].reminderTime)
        assertEquals(Recurrence.WEEKLY, tasks[1].recurrence)
        assertEquals(LocalDate.of(2026, 1, 14), tasks[1].completedDate)
        assertTrue(tasks[1].isArchived)
    }

    @Test
    fun `each validation rule reports its own message`() = runTest {
        val live = task(1, "ok", id = 1)
        val cases = listOf(
            backup(listOf(home), listOf(live), version = "2.0") to R.string.err_backup_version,
            backup(listOf(home, home.copy(title = "Dup")), listOf(live)) to R.string.err_cat_duplicate_id,
            backup(listOf(home.copy(id = 0)), emptyList()) to R.string.err_cat_bad_id,
            backup(listOf(home.copy(title = " ")), emptyList()) to R.string.err_cat_empty_title,
            backup(listOf(home), listOf(live, live.copy(text = "dup"))) to R.string.err_task_duplicate_id,
            backup(listOf(home), listOf(live.copy(text = "  "))) to R.string.err_task_empty_text,
            backup(listOf(home), listOf(live.copy(categoryId = 9))) to R.string.err_task_bad_category,
            backup(listOf(home.copy(isDeleted = true)), listOf(live)) to R.string.err_task_live_deleted_category,
        )

        cases.forEach { (bad, expectedRes) ->
            val e = assertFails<BackupValidationException> { BackupManager.import(ctx, uriFor(bad), taskRepo) }
            assertEquals(ctx.resources.getResourceEntryName(expectedRes), ctx.resources.getResourceEntryName(e.resId))
        }
    }

    @Test
    fun `validation errors carry the offending ids`() = runTest {
        val bad = backup(listOf(home), listOf(task(1, "orphan", id = 7).copy(categoryId = 9)))

        val e = assertFails<BackupValidationException> { BackupManager.import(ctx, uriFor(bad), taskRepo) }

        assertArrayEquals(arrayOf<Any>(7, 9), e.args)
    }

    @Test
    fun `archived tasks may live in deleted categories`() = runTest {
        val ok = backup(listOf(home.copy(isDeleted = true)), listOf(task(1, "old", id = 1, isArchived = true)))

        BackupManager.import(ctx, uriFor(ok), taskRepo)

        assertEquals(1, taskRepo.snapshot().size)
    }

    @Test
    fun `a rejected import leaves existing data untouched`() = runTest {
        seed()
        val before = taskRepo.snapshot()

        assertFails<BackupValidationException> {
            BackupManager.import(ctx, uriFor(backup(emptyList(), emptyList(), version = "0.9")), taskRepo)
        }
        assertFails<SerializationException> {
            BackupManager.import(ctx, uriFor("{ not json".toByteArray()), taskRepo)
        }

        assertEquals(before, taskRepo.snapshot())
    }
}
