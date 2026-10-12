// ui/admin/ToolsViewModelTest.kt
package com.taskfree.app.ui.admin

import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskfree.app.data.database.AppDatabase
import com.taskfree.app.data.repository.TaskRepository
import com.taskfree.app.domain.model.Recurrence
import com.taskfree.app.domain.model.TaskStatus
import com.taskfree.app.testutil.MainDispatcherRule
import com.taskfree.app.testutil.datesAt
import com.taskfree.app.testutil.inMemoryDb
import com.taskfree.app.testutil.insertCategory
import com.taskfree.app.testutil.insertTask
import com.taskfree.app.testutil.task
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import com.taskfree.app.R
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ToolsViewModelTest {

    @get:Rule
    val main = MainDispatcherRule(StandardTestDispatcher())

    private val today = LocalDate.of(2026, 10, 6)
    private lateinit var db: AppDatabase
    private lateinit var taskRepo: TaskRepository
    private lateinit var vm: ToolsViewModel

    @Before
    fun setUp() {
        db = inMemoryDb()
        taskRepo = TaskRepository(db, datesAt(today))
        vm = ToolsViewModel(taskRepo, db, main.dispatcher)
    }

    @After
    fun tearDown() {
        vm.viewModelScope.cancel()
        db.close()
    }

    @Test
    fun `archiving old completed tasks reports and refreshes`() = runTest {
        val cat = db.insertCategory()
        val old = db.insertTask(task(cat, "old", status = TaskStatus.DONE, completedDate = today.minusDays(1)))
        val refreshed = async(UnconfinedTestDispatcher(testScheduler)) { vm.refresh.first() }

        vm.archiveOldCompleted().join()

        refreshed.await()
        assertEquals(ToolsEvent.Archived(1), vm.events.first())
        assertTrue(taskRepo.taskById(old)!!.isArchived)
    }

    @Test
    fun `archiving old completed repeats preserves one-offs and reports and refreshes`() = runTest {
        val cat = db.insertCategory()
        val repeat = db.insertTask(task(
            cat, "Repeat", recurrence = Recurrence.DAILY, status = TaskStatus.DONE,
            completedDate = today.minusDays(1)
        ))
        val oneOff = db.insertTask(task(
            cat, "One-off", status = TaskStatus.DONE, completedDate = today.minusDays(1)
        ))
        val refreshed = async(UnconfinedTestDispatcher(testScheduler)) { vm.refresh.first() }

        vm.archiveOldCompletedRepeats().join()

        refreshed.await()
        assertEquals(ToolsEvent.Archived(1), vm.events.first())
        assertTrue(taskRepo.taskById(repeat)!!.isArchived)
        assertFalse(taskRepo.taskById(oneOff)!!.isArchived)
    }

    @Test
    fun `completed repeats preview lists what archiving with its date then archives`() = runTest {
        val cat = db.insertCategory("Home")
        repeat(2) {
            db.insertTask(task(
                cat, "Water plants", recurrence = Recurrence.DAILY, status = TaskStatus.DONE,
                completedDate = today.minusDays(1L + it)
            ))
        }
        db.insertTask(task(cat, "One-off", status = TaskStatus.DONE, completedDate = today.minusDays(1)))

        val preview = vm.completedRepeatsPreview()
        assertEquals(today, preview.asOf)
        assertEquals(listOf("Water plants" to 2), preview.groups.map { it.text to it.count })
        assertEquals("Home", preview.groups.single().catTitle)

        vm.archiveOldCompletedRepeats(asOf = preview.asOf).join()

        assertEquals(ToolsEvent.Archived(preview.total), vm.events.first())
        assertEquals(0, vm.completedRepeatsPreview().total)
    }

    @Test
    fun `deleting archived tasks reports and refreshes`() = runTest {
        val cat = db.insertCategory()
        val keep = db.insertTask(task(cat, "keep"))
        db.insertTask(task(cat, "gone", isArchived = true))
        val refreshed = async(UnconfinedTestDispatcher(testScheduler)) { vm.refresh.first() }

        vm.deleteArchived().join()

        refreshed.await()
        assertEquals(ToolsEvent.Deleted, vm.events.first())
        assertEquals(listOf(keep), taskRepo.snapshot().map { it.id })
    }

    @Test
    fun `show-archived toggles`() {
        assertFalse(vm.uiState.value.showArchived)
        vm.toggleShowArchived()
        assertTrue(vm.uiState.value.showArchived)
        vm.toggleShowArchived()
        assertFalse(vm.uiState.value.showArchived)
    }

    @Test
    fun `identical zero counts are buffered once each without replay`() = runTest {
        vm.archiveOldCompleted().join()
        vm.archiveOldCompletedRepeats().join()
        assertEquals(listOf(ToolsEvent.Archived(0), ToolsEvent.Archived(0)), vm.events.take(2).toList())
        val events = mutableListOf<ToolsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
        runCurrent()
        assertTrue(events.isEmpty())
    }

    @Test
    fun `consecutive positive counts each produce an event and refresh`() = runTest {
        val category = db.insertCategory()
        val refreshes = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.refresh.toList(refreshes) }
        repeat(2) {
            db.insertTask(task(category, status = TaskStatus.DONE, completedDate = today.minusDays(1)))
            vm.archiveOldCompleted().join()
        }
        assertEquals(listOf(ToolsEvent.Archived(1), ToolsEvent.Archived(1)), vm.events.take(2).toList())
        assertEquals(2, refreshes.size)
    }

    @Test
    fun `all failed bulk actions emit failure without success or refresh`() = runTest {
        val cat = db.insertCategory()
        db.insertTask(task(cat, status = TaskStatus.DONE, completedDate = today.minusDays(1),
            recurrence = Recurrence.DAILY))
        db.insertTask(task(cat, isArchived = true))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_update BEFORE UPDATE ON Task BEGIN SELECT RAISE(ABORT, 'injected'); END")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_delete BEFORE DELETE ON Task BEGIN SELECT RAISE(ABORT, 'injected'); END")
        val refreshes = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.refresh.toList(refreshes) }
        vm.archiveOldCompleted().join()
        vm.archiveOldCompletedRepeats().join()
        vm.deleteArchived().join()
        assertEquals(List(3) { ToolsEvent.Failed }, vm.events.take(3).toList())
        assertTrue(refreshes.isEmpty())
    }

    @Test
    fun `cancellation does not become failure or refresh`() = runTest {
        val events = mutableListOf<ToolsEvent>()
        val refreshes = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.refresh.toList(refreshes) }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transaction = launch(Dispatchers.IO) {
            db.withTransaction {
                entered.complete(Unit)
                release.await()
            }
        }
        try {
            entered.await()
            val operation = vm.archiveOldCompleted()
            runCurrent() // The action starts, then waits for the held Room transaction.
            assertFalse(operation.isCompleted)
            operation.cancel()
            // The blocked UPDATE cannot observe cancellation until the transaction ends.
            release.complete(Unit)
            operation.join()
            assertTrue(operation.isCancelled)
        } finally {
            release.complete(Unit)
            transaction.join()
        }
        assertTrue(events.isEmpty())
        assertTrue(refreshes.isEmpty())
    }

    @Test
    fun `archive counts are localized including zero and plural`() {
        val context: android.content.Context = androidx.test.core.app.ApplicationProvider.getApplicationContext()
        for ((language, expected) in listOf(
            "en" to listOf("Archived 0 tasks", "Archived 1 task", "Archived 12 tasks"),
            "es" to listOf("Se han archivado 0 tareas", "Se ha archivado 1 tarea", "Se han archivado 12 tareas")
        )) {
            val config = android.content.res.Configuration(context.resources.configuration)
            config.setLocale(java.util.Locale.forLanguageTag(language))
            val resources = context.createConfigurationContext(config).resources
            assertEquals(expected, listOf(0, 1, 12).map { resources.getQuantityString(R.plurals.archived_task_count, it, it) })
        }
    }

    private val exportUri = android.net.Uri.parse("content://exports/document/new-export")

    @Test fun `export produces valid JSON and opens writes and closes off the main thread`() = runTest {
        val category = db.insertCategory("Exported category")
        db.insertTask(task(category, "Exported task"))
        val ioVm = ToolsViewModel(taskRepo, db)
        val operations = mutableListOf<String>()
        fun record(name: String) {
            assertFalse(Thread.currentThread() == android.os.Looper.getMainLooper().thread)
            operations += name
        }
        val output = object : java.io.ByteArrayOutputStream() {
            override fun write(bytes: ByteArray) { record("write"); super.write(bytes) }
            override fun close() { record("close"); super.close() }
        }
        ioVm.exportBackup(exportUri, { uri ->
            assertEquals(exportUri, uri); record("open"); output
        })
        assertEquals(listOf("open", "write", "close"), operations)
        val result = kotlinx.serialization.json.Json.decodeFromString<com.taskfree.app.data.repository.Backup>(output.toString("UTF-8"))
        assertEquals("Exported category", result.categories.single().title)
        assertEquals("Exported task", result.tasks.single().text)
    }

    @Test fun `export open write and close failures propagate the original failure`() = runTest {
        val ioVm = ToolsViewModel(taskRepo, db)
        for (stage in listOf("open", "write", "close")) {
            val failure = java.io.IOException(stage)
            val actual = com.taskfree.app.testutil.assertFails<java.io.IOException> {
                ioVm.exportBackup(exportUri, {
                    assertFalse(Thread.currentThread() == android.os.Looper.getMainLooper().thread)
                    if (stage == "open") throw failure
                    object : java.io.OutputStream() {
                        override fun write(value: Int) {
                            assertFalse(Thread.currentThread() == android.os.Looper.getMainLooper().thread)
                            if (stage == "write") throw failure
                        }
                        override fun close() {
                            assertFalse(Thread.currentThread() == android.os.Looper.getMainLooper().thread)
                            if (stage == "close") throw failure
                        }
                    }
                })
            }
            // Coroutine stack-trace recovery can wrap a copy of the original exception.
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === failure })
        }
    }

    @Test fun `export validation and build failures never open the destination`() = runTest {
        val category = db.insertCategory()
        repeat(24) { db.insertTask(task(category, "x".repeat(900_000))) }
        val failure = com.taskfree.app.testutil.assertFails<com.taskfree.app.data.repository.BackupManager.BackupValidationException> {
            vm.exportBackup(exportUri, { error("Must validate before opening") })
        }
        assertEquals(R.string.err_backup_too_large, failure.resId)
        db.close()
        com.taskfree.app.testutil.assertFails<IllegalStateException> {
            vm.exportBackup(exportUri, { error("Build must finish before opening") })
        }
    }

    @Test fun `export cancellation during writing closes the partial document and propagates`() = runTest {
        val ioVm = ToolsViewModel(taskRepo, db)
        var closed = false
        val output = java.io.ByteArrayOutputStream()
        val cancellation = kotlinx.coroutines.CancellationException("cancel writing")
        val exporting = async {
            ioVm.exportBackup(exportUri, {
                object : java.io.OutputStream() {
                    override fun write(value: Int) { output.write(value); throw cancellation }
                    override fun close() { closed = true }
                }
            })
        }
        val actual = com.taskfree.app.testutil.assertFails<kotlinx.coroutines.CancellationException> { exporting.await() }
        assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === cancellation })
        assertTrue(exporting.isCancelled)
        assertTrue(closed)
        assertEquals(1, output.size())
    }

    @Test fun `cancellation after successful close retains complete export`() = runTest {
        val ioVm = ToolsViewModel(taskRepo, db)
        val output = java.io.ByteArrayOutputStream()
        var closed = false
        val exporting = async {
            val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!
            ioVm.exportBackup(exportUri) {
                object : java.io.FilterOutputStream(output) {
                    override fun close() { super.close(); closed = true; job.cancel() }
                }
            }
        }
        com.taskfree.app.testutil.assertFails<kotlinx.coroutines.CancellationException> { exporting.await() }
        assertTrue(closed)
        kotlinx.serialization.json.Json.decodeFromString<com.taskfree.app.data.repository.Backup>(output.toString("UTF-8"))
    }

    @Test fun `provider export truncates longer JSON and never falls back or deletes on failure`() = runTest {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val target = java.io.File.createTempFile("export", ".json", context.cacheDir)
        val modes = mutableListOf<String>()
        var deletions = 0
        var openFailure: java.io.IOException? = null
        val provider = object : android.content.ContentProvider() {
            override fun onCreate() = true
            override fun openFile(uri: android.net.Uri, mode: String): android.os.ParcelFileDescriptor {
                assertFalse(Thread.currentThread() == android.os.Looper.getMainLooper().thread)
                modes += mode
                openFailure?.let { throw it }
                val flags = android.os.ParcelFileDescriptor.MODE_WRITE_ONLY or
                    if (mode == "wt") android.os.ParcelFileDescriptor.MODE_TRUNCATE else 0
                return android.os.ParcelFileDescriptor.open(target, flags)
            }
            override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle? {
                if (method == "android:deleteDocument") deletions++
                return null
            }
            override fun delete(uri: android.net.Uri, selection: String?, selectionArgs: Array<out String>?): Int {
                deletions++; return 0
            }
            override fun query(uri: android.net.Uri, projection: Array<out String>?, selection: String?,
                               selectionArgs: Array<out String>?, sortOrder: String?): android.database.Cursor? = null
            override fun getType(uri: android.net.Uri) = "application/json"
            override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = null
            override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?,
                                selectionArgs: Array<out String>?) = 0
        }
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "exports" })
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("exports", provider)
        val ioVm = ToolsViewModel(taskRepo, db)
        try {
            db.insertCategory("Original " + "x".repeat(1000))
            ioVm.exportBackup(context.contentResolver, exportUri)
            val longer = target.length()
            taskRepo.replaceAll(emptyList(), emptyList())
            ioVm.exportBackup(context.contentResolver, exportUri)
            val parsed = kotlinx.serialization.json.Json.decodeFromString<com.taskfree.app.data.repository.Backup>(target.readText())
            assertTrue(parsed.categories.isEmpty())
            assertTrue(target.length() < longer)
            org.junit.Assert.assertArrayEquals(com.taskfree.app.data.repository.BackupManager.encode(parsed), target.readBytes())
            assertEquals(listOf("wt", "wt"), modes)
            val original = target.readBytes()
            openFailure = java.io.FileNotFoundException("provider unavailable")
            com.taskfree.app.testutil.assertFails<java.io.FileNotFoundException> {
                ioVm.exportBackup(context.contentResolver, exportUri)
            }
            assertEquals(listOf("wt", "wt", "wt"), modes)
            org.junit.Assert.assertArrayEquals(original, target.readBytes())
            assertEquals(0, deletions)
            assertTrue(target.exists())
        } finally { target.delete() }
    }
}
