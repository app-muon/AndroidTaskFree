// data/repository/BackupManagerTest.kt
package com.taskfree.app.data.repository

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.Room
import com.taskfree.app.BuildConfig
import com.taskfree.app.R
import com.taskfree.app.data.backup.BackupCrypto
import com.taskfree.app.data.database.AppDatabase
import com.taskfree.app.data.entities.Category
import com.taskfree.app.data.entities.Task
import com.taskfree.app.data.entities.TaskNote
import com.taskfree.app.data.entities.TASK_NOTE_LIMIT
import com.taskfree.app.data.repository.BackupManager.BackupValidationException
import com.taskfree.app.domain.model.Recurrence
import com.taskfree.app.domain.model.TaskInput
import com.taskfree.app.domain.model.TaskStatus
import com.taskfree.app.testutil.assertFails
import com.taskfree.app.testutil.datesAt
import com.taskfree.app.testutil.inMemoryDb
import com.taskfree.app.testutil.insertCategory
import com.taskfree.app.testutil.insertTask
import com.taskfree.app.testutil.task
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

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

    private suspend fun importFile(uri: Uri, repo: TaskRepository) =
        BackupManager.restore((BackupManager.read(ctx, uri) as BackupSource.Plain).backup, repo)

    private val phrase = listOf("apple", "brick", "cloud", "delta", "eagle", "flame", "grape", "house")

    private suspend fun readError(bytes: ByteArray): Int =
        assertFails<BackupValidationException> { BackupManager.read(ctx, uriFor(bytes)) }.resId

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
            ).copy(
                originalCreatedAt = Instant.parse("2026-01-02T08:15:30.123Z"),
                occurrenceCreatedAt = Instant.parse("2026-10-05T18:45:12.456Z")
            )
        )
        db.insertTask(
            task(
                home, "Done thing", status = TaskStatus.DONE, completedDate = today.minusDays(2),
                singleOrder = 1, allOrder = 0
            )
        )
        db.insertTask(task(gone, "Archived orphan", isArchived = true))
        val parent = db.taskDao().getAllNow().first().id
        db.taskNoteDao().insertAll(listOf(
            TaskNote(8, parent, "First\nsecond", Instant.parse("2026-10-06T08:15:30.123Z"),
                Instant.parse("2026-10-06T09:15:30.456Z")),
            TaskNote(3, parent, "Unedited", Instant.parse("2026-10-05T08:15:30.789Z"))
        ))
    }

    @Test
    fun `export then import restores identical data`() = runTest {
        seed()
        val bytes = BackupManager.buildJson(db)
        val expectedCats = catRepo.snapshot().sortedBy { it.id }
        val expectedTasks = taskRepo.snapshot().sortedBy { it.id }

        val target = inMemoryDb()
        try {
            importFile(uriFor(bytes), TaskRepository(target, datesAt(today)))

            assertEquals(expectedCats, target.categoryDao().getAllNow().sortedBy { it.id })
            assertEquals(expectedTasks, target.taskDao().getAllNow().sortedBy { it.id })
            assertEquals(db.taskNoteDao().getAllNow(), target.taskNoteDao().getAllNow())
        } finally {
            target.close()
        }
    }

    @Test
    fun `export format has metadata and omits isDeleted for live categories`() = runTest {
        seed()
        val root = Json.parseToJsonElement(BackupManager.buildJson(db).decodeToString()).jsonObject

        assertEquals("1.1", root["version"]!!.jsonPrimitive.content)
        assertEquals(BuildConfig.VERSION_NAME, root["app_version"]!!.jsonPrimitive.content)
        val cats = root["categories"]!!.jsonArray.map { it.jsonObject }
        assertFalse("isDeleted" in cats.single { it["title"]!!.jsonPrimitive.content == "Home" })
        assertEquals("true", cats.single { it["title"]!!.jsonPrimitive.content == "Gone" }["isDeleted"]!!.jsonPrimitive.content)
        val task = root["tasks"]!!.jsonArray.first().jsonObject
        assertEquals("2026-10-06", task["due"]!!.jsonPrimitive.content)
        assertEquals("2026-01-02T08:15:30.123Z", task["originalCreatedAt"]!!.jsonPrimitive.content)
        assertEquals("2026-10-05T18:45:12.456Z", task["occurrenceCreatedAt"]!!.jsonPrimitive.content)
    }

    @Test
    fun `older backups without isDeleted import and ignore unknown keys`() = runTest {
        seed()
        val bytes = javaClass.getResourceAsStream("/backups/v1_0_no_isDeleted.json")!!.readBytes()

        importFile(uriFor(bytes), taskRepo)
        assertTrue(db.taskNoteDao().getAllNow().isEmpty())

        val cat = catRepo.snapshot().single()
        assertEquals("Home", cat.title)
        assertFalse(cat.isDeleted)
        val tasks = taskRepo.snapshot().sortedBy { it.id }
        assertEquals(listOf("Fix tap", "Bins"), tasks.map { it.text })
        assertEquals(Instant.parse("2026-01-20T09:00:00Z"), tasks[0].reminderTime)
        assertEquals(Recurrence.WEEKLY, tasks[1].recurrence)
        assertEquals(LocalDate.of(2026, 1, 14), tasks[1].completedDate)
        assertTrue(tasks[1].isArchived)
        tasks.forEach {
            assertNull(it.originalCreatedAt)
            assertNull(it.occurrenceCreatedAt)
            assertNull(it.sourceTaskId)
        }
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
            val e = assertFails<BackupValidationException> { importFile(uriFor(bad), taskRepo) }
            assertEquals(ctx.resources.getResourceEntryName(expectedRes), ctx.resources.getResourceEntryName(e.resId))
        }
    }

    @Test
    fun `validation errors carry the offending ids`() = runTest {
        val bad = backup(listOf(home), listOf(task(1, "orphan", id = 7).copy(categoryId = 9)))

        val e = assertFails<BackupValidationException> { importFile(uriFor(bad), taskRepo) }

        assertArrayEquals(arrayOf<Any>(7, 9), e.args)
    }

    @Test
    fun `archived tasks may live in deleted categories`() = runTest {
        val ok = backup(listOf(home.copy(isDeleted = true)), listOf(task(1, "old", id = 1, isArchived = true)))

        importFile(uriFor(ok), taskRepo)

        assertEquals(1, taskRepo.snapshot().size)
    }

    @Test
    fun `backup preserves occurrence links even when the child precedes its parent`() = runTest {
        val category = db.insertCategory()
        val id = taskRepo.createTask(TaskInput("Water plants", today, Recurrence.DAILY, category))
        val nextId = taskRepo.updateTaskStatus(id, TaskStatus.DONE).nextCreatedId!!
        val exported = json.decodeFromString(Backup.serializer(), BackupManager.buildJson(db).decodeToString())
        val reordered = exported.copy(tasks = exported.tasks.sortedByDescending { it.id })

        for (backup in listOf(exported, reordered)) {
            importFile(uriFor(backup), taskRepo)
            assertEquals(exported.tasks.sortedBy { it.id }, taskRepo.snapshot().sortedBy { it.id })
            assertEquals(exported.categories, catRepo.snapshot())
            assertEquals(id, taskRepo.taskById(nextId)!!.sourceTaskId)
        }
        val laterRepo = TaskRepository(db, datesAt(today.plusDays(2)))
        assertEquals(nextId, laterRepo.updateTaskStatus(id, TaskStatus.TODO).nextDeletedId)
        assertNull(laterRepo.taskById(nextId))
    }

    @Test
    fun `invalid occurrence links reject a backup without replacing existing data`() = runTest {
        seed()
        val before = taskRepo.snapshot()
        val categoriesBefore = catRepo.snapshot()
        val parent = task(1, "parent", id = 10)
        val child = task(1, "child", id = 11).copy(sourceTaskId = 10)
        val invalidSets = listOf(
            listOf(parent, child.copy(sourceTaskId = 999)),
            listOf(parent.copy(sourceTaskId = 11), child),
            listOf(parent.copy(sourceTaskId = 10)),
            listOf(parent, child, child.copy(id = 12))
        )
        for (tasks in invalidSets) {
            val error = assertFails<BackupValidationException> {
                importFile(uriFor(backup(listOf(home), tasks)), taskRepo)
            }
            assertEquals(R.string.err_task_occurrence_links, error.resId)
            assertEquals(before, taskRepo.snapshot())
            assertEquals(categoriesBefore, catRepo.snapshot())
        }
    }

    @Test
    fun `a rejected import leaves existing data untouched`() = runTest {
        seed()
        val before = taskRepo.snapshot()

        assertFails<BackupValidationException> {
            importFile(uriFor(backup(emptyList(), emptyList(), version = "0.9")), taskRepo)
        }
        val damaged = assertFails<BackupValidationException> {
            importFile(uriFor("{ not json".toByteArray()), taskRepo)
        }
        assertEquals(R.string.err_backup_damaged, damaged.resId)

        assertEquals(before, taskRepo.snapshot())
    }

    @Test
    fun `an encrypted backup unlocks only with its phrase and restores identical data`() = runTest {
        seed()
        val expected = BackupManager.snapshot(db)
        val key = BackupCrypto.deriveKey(phrase, iterations = 1_000)
        val file = BackupCrypto.encrypt(BackupManager.encode(expected), key)

        val source = BackupManager.read(ctx, uriFor(file)) as BackupSource.Encrypted
        assertNull(BackupManager.unlock(source, phrase.reversed()))
        val target = inMemoryDb()
        try {
            BackupManager.restore(BackupManager.unlock(source, phrase)!!, TaskRepository(target, datesAt(today)))

            assertEquals(expected.categories, target.categoryDao().getAllNow().sortedBy { it.id })
            assertEquals(expected.tasks, target.taskDao().getAllNow().sortedBy { it.id })
            assertEquals(expected.notes, target.taskNoteDao().getAllNow())
        } finally {
            target.close()
        }
    }

    @Test
    fun `plain backups may start with a byte order mark and whitespace`() = runTest {
        val text = json.encodeToString(Backup.serializer(), backup(listOf(home), listOf(task(1, "ok", id = 1))))

        val source = BackupManager.read(ctx, uriFor("${Char(0xFEFF)} \n$text".toByteArray()))

        assertEquals("ok", (source as BackupSource.Plain).backup.tasks.single().text)
    }

    @Test
    fun `files that are not complete backups are rejected before restoring`() = runTest {
        val encrypted = BackupCrypto.encrypt("{}".toByteArray(), BackupCrypto.deriveKey(phrase, iterations = 1_000))

        assertEquals(R.string.err_not_a_backup, readError("hello".toByteArray()))
        assertEquals(R.string.err_not_a_backup, readError(ByteArray(0)))
        assertEquals(R.string.err_backup_incomplete, readError(encrypted.copyOf(encrypted.size - 1)))
        assertEquals(R.string.err_backup_too_large, readError(ByteArray(BackupManager.MAX_FILE_BYTES + 1) { ' '.code.toByte() }))
    }

    @Test
    fun `plain and encrypted files share the same inclusive final file limit`() = runTest {
        val base = BackupManager.encode(backup(listOf(home), listOf(task(1, "ok", id = 1))))
        val key = BackupCrypto.deriveKey(phrase, iterations = 1_000)
        assertEquals(62, BackupCrypto.encrypt(base, key).size - base.size)
        fun padded(size: Int) = base + ByteArray(size - base.size) { 32 }
        for (size in listOf(BackupManager.MAX_FILE_BYTES - 1, BackupManager.MAX_FILE_BYTES)) {
            assertTrue(BackupManager.read(ctx, uriFor(padded(size))) is BackupSource.Plain)
            val ciphertext = BackupCrypto.encrypt(padded(size - BackupCrypto.OVERHEAD_BYTES), key)
            assertEquals(size, ciphertext.size)
            val read = BackupManager.read(ctx, uriFor(ciphertext)) as BackupSource.Encrypted
            assertEquals("ok", BackupManager.unlock(read, phrase)!!.tasks.single().text)
        }
        assertEquals(R.string.err_backup_too_large, readError(padded(BackupManager.MAX_FILE_BYTES + 1)))
        val largePlain = padded(BackupManager.MAX_FILE_BYTES - BackupCrypto.OVERHEAD_BYTES + 1)
        assertEquals(R.string.err_backup_too_large,
            assertFails<BackupValidationException> { BackupCrypto.encrypt(largePlain, key) }.resId)
        // Include a correctly marked encrypted file above the final-file bound.
        val ciphertext = BackupCrypto.encrypt(padded(BackupManager.MAX_FILE_BYTES - BackupCrypto.OVERHEAD_BYTES), key)
        assertEquals(R.string.err_backup_too_large, readError(ciphertext + byteArrayOf(0)))
        val category = db.insertCategory()
        repeat(24) { db.insertTask(task(category, "x".repeat(900_000))) }
        assertEquals(R.string.err_backup_too_large,
            assertFails<BackupValidationException> { BackupManager.buildJson(db) }.resId)
    }

    @Test
    fun `snapshot encloses all three table reads in one transaction`() = runTest {
        val recording = AtomicBoolean(false)
        val queries = Collections.synchronizedList(mutableListOf<Pair<Long, String>>())
        val transactionalDb = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .setQueryCallback({ sql, _ ->
                if (recording.get()) queries += Thread.currentThread().id to sql
            }, java.util.concurrent.Executor { it.run() }).build()
        try {
            val repo = TaskRepository(transactionalDb)
            repo.replaceAll(listOf(home), listOf(task(1, "before", id = 1)))
            recording.set(true)
            val captured = BackupManager.snapshot(transactionalDb)
            recording.set(false)
            val recorded = synchronized(queries) { queries.toList() }
            val readerThread = recorded.single { it.second == "SELECT * FROM Category" }.first
            assertEquals(readerThread, recorded.single { it.second == "SELECT * FROM Task" }.first)
            assertEquals(readerThread, recorded.single { it.second == "SELECT * FROM TaskNote ORDER BY id" }.first)
            // Room's invalidation tracker may transact concurrently on another connection/thread.
            val boundariesAndReads = recorded.filter { it.first == readerThread }.mapNotNull { (_, sql) ->
                when {
                    sql.startsWith("BEGIN") -> "begin"
                    sql == "END TRANSACTION" -> "end"
                    sql == "SELECT * FROM Category" -> "categories"
                    sql == "SELECT * FROM Task" -> "tasks"
                    sql == "SELECT * FROM TaskNote ORDER BY id" -> "notes"
                    else -> null
                }
            }
            assertEquals(recorded.toString(), listOf("begin", "categories", "tasks", "notes", "end"),
                boundariesAndReads.take(boundariesAndReads.indexOf("end") + 1))
            assertEquals(listOf(home), captured.categories)
            assertEquals("before", captured.tasks.single().text)
            assertEquals(1, captured.tasks.single().categoryId)
        } finally { transactionalDb.close() }
    }

    @Test
    fun `nonpositive task ids and links report the offending id before replacing data`() = runTest {
        seed()
        val beforeTasks = taskRepo.snapshot()
        val beforeCategories = catRepo.snapshot()
        for (invalidId in listOf(0, -1, Int.MIN_VALUE)) {
            val invalid = task(1, "Invalid", id = invalidId)
            val linked = task(1, "References invalid ID", id = 100).copy(sourceTaskId = invalidId)
            for (tasks in listOf(listOf(invalid), listOf(linked, invalid), listOf(linked))) {
                val error = assertFails<BackupValidationException> {
                    importFile(uriFor(backup(listOf(home), tasks)), taskRepo)
                }
                assertEquals(R.string.err_task_bad_id, error.resId)
                assertArrayEquals(arrayOf<Any>(invalidId), error.args)
                assertEquals(beforeTasks, taskRepo.snapshot())
                assertEquals(beforeCategories, catRepo.snapshot())
            }
        }
    }

    @Test
    fun `invalid notes reject restore before touching existing data`() = runTest {
        seed()
        val before = BackupManager.snapshot(db)
        val note = before.notes.first()
        val invalid = listOf(
            listOf(note.copy(id = 0)), listOf(note.copy(id = -1)), listOf(note, note),
            listOf(note.copy(taskId = 999)), listOf(note.copy(text = " \n ")),
            listOf(note.copy(text = "x".repeat(TASK_NOTE_LIMIT + 1)))
        )
        for (notes in invalid) {
            val bad = before.copy(notes = notes)
            assertEquals(R.string.err_backup_notes,
                assertFails<BackupValidationException> { BackupManager.restore(bad, taskRepo) }.resId)
            assertFails<IllegalArgumentException> { taskRepo.replaceAll(bad.categories, bad.tasks, bad.notes) }
            assertEquals(before.tasks, taskRepo.snapshot().sortedBy { it.id })
            assertEquals(before.notes, db.taskNoteDao().getAllNow())
        }
    }
}
