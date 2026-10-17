package com.taskfree.app.data.backup

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskfree.app.Prefs
import com.taskfree.app.data.AppDatabaseFactory
import com.taskfree.app.data.RealDatabaseMigrator
import com.taskfree.app.data.database.AppDatabase
import com.taskfree.app.data.repository.TaskRepository
import com.taskfree.app.testutil.insertCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AutoBackupTest {
    @get:Rule val mainDispatcher = com.taskfree.app.testutil.MainDispatcherRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val folder = Uri.parse("content://test/tree/backups")
    private val file = BackupTestFile()
    private val store = BackupTestStore()
    private val scheduler = TestCoroutineScheduler()
    private val saveScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private lateinit var db: AppDatabase
    private lateinit var saver: BackupSaver

    @Before fun prepare() = runBlocking {
        AutoBackup.resetProcessForTesting()
        RealDatabaseMigrator.resetProcessForTesting()
        RealDatabaseMigrator.resetEncryption(app)
        RealDatabaseMigrator.start(app).join()
        db = AppDatabaseFactory.getDatabase(app)
        Prefs.savePhrase(app, listOf("apple", "brick", "cloud", "delta", "eagle", "flame", "grape", "house"))
        saver = BackupSaver(app, file, { db }, iterations = 1_000, prefs = store)
        AutoBackup.configureForTesting(saver, saveScope)
    }

    @After fun clean() = runBlocking {
        val reset = async(Dispatchers.Default) { AutoBackup.resetProcessForTesting() }
        pumpUntil { reset.isCompleted }
        reset.await()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, holds())
        RealDatabaseMigrator.resetProcessForTesting()
        RealDatabaseMigrator.resetEncryption(app)
    }

    private suspend fun pumpUntil(condition: () -> Boolean) = withTimeout(15_000) {
        do {
            scheduler.runCurrent()
            delay(10)
        } while (!condition())
    }

    private suspend fun finishSaves(watching: Boolean = false) {
        pumpUntil { saveScope.coroutineContext[Job]!!.children.count() == if (watching) 1 else 0 }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun holds(): Int = BackupSaveService::class.java.getDeclaredField("holds").let {
        it.isAccessible = true
        it.getInt(null)
    }

    private fun serviceStarts(): Int {
        var count = 0
        while (shadowOf(app).nextStartedService != null) count++
        return count
    }

    @Test fun `every hold is released once when saves are cancelled before execution`() = runBlocking {
        repeat(3) { AutoBackup.backUpNow(app) }
        assertEquals(3, serviceStarts())
        assertEquals(3, holds())
        saveScope.coroutineContext[Job]!!.children.toList().forEach { it.cancel() }
        finishSaves()
        assertEquals(0, holds())
        assertTrue(file.writes.isEmpty())
    }

    @Test fun `duplicate setup is rejected while the first coroutine is still queued`() = runBlocking {
        assertEquals(AutoBackup.EnableResult.STARTED, AutoBackup.enable(app, folder))
        assertEquals(AutoBackup.EnableResult.BUSY, AutoBackup.setupRejection(app))
        assertEquals(AutoBackup.EnableResult.BUSY, AutoBackup.enable(app, folder))
        assertEquals(1, serviceStarts())
        assertFalse(saver.status.value.saving) // Neither coroutine has run yet.
        finishSaves(watching = true)
        assertEquals(2, file.creations)
        assertEquals(2, file.writes.size)
        AutoBackup.enable(app, folder)
        finishSaves(watching = true)
        assertEquals(4, file.creations) // A later same-folder setup deliberately creates a fresh pair.
    }

    @Test fun `setup reports not ready and restart required without taking service holds`() = runBlocking {
        RealDatabaseMigrator.resetProcessForTesting()
        assertEquals(AutoBackup.EnableResult.NOT_READY, AutoBackup.setupRejection(app))
        assertEquals(AutoBackup.EnableResult.NOT_READY, AutoBackup.enable(app, folder))
        RealDatabaseMigrator.start(app).join()
        db = AppDatabaseFactory.getDatabase(app)
        store.failAfter = 1
        saver.enable(folder)
        assertEquals(AutoBackup.EnableResult.RESTART_REQUIRED, AutoBackup.setupRejection(app))
        assertEquals(AutoBackup.EnableResult.RESTART_REQUIRED, AutoBackup.enable(app, folder))
        assertEquals(0, serviceStarts())
        assertEquals(0, holds())
    }

    @Test fun `paused errors never hold service and valid grant recovery retries`() = runBlocking {
        db.insertCategory("Home")
        saver.enable(folder)
        val good = store.disk!!
        for (kind in listOf(BackupError.Kind.ACTIVE_DAMAGED, BackupError.Kind.PHRASE_CHANGED, BackupError.Kind.NO_ACCESS)) {
            store.disk = good.copy(error = BackupError(kind, java.time.Instant.now()), pending = true)
            saver = BackupSaver(app, file, { db }, iterations = 1_000, prefs = store)
            AutoBackup.configureForTesting(saver, saveScope)
            file.writable = kind != BackupError.Kind.NO_ACCESS
            repeat(3) {
                AutoBackup.onLeaving(app)
                finishSaves()
                assertEquals(0, serviceStarts())
                assertEquals(0, holds())
                assertEquals(kind, store.disk!!.error!!.kind)
            }
        }
        file.writable = true
        AutoBackup.onLeaving(app)
        finishSaves()
        assertEquals(0, serviceStarts()) // The last eligibility refresh was paused; saving still recovers.
        assertNull(store.disk!!.error)
        assertEquals(0, holds())
    }

    @Test fun `oversized attempt suppresses holds until watcher invalidation before debounce`() = runBlocking {
        db.insertCategory("Home")
        saver.enable(folder)
        file.writeHook = { throw com.taskfree.app.data.repository.BackupManager.BackupValidationException(com.taskfree.app.R.string.err_backup_too_large) }
        saver.save(true)
        AutoBackup.onForeground(app)
        finishSaves(watching = true)
        repeat(3) {
            AutoBackup.onLeaving(app)
            finishSaves(watching = true)
            assertEquals(0, serviceStarts())
        }
        db.insertCategory("Edit")
        pumpUntil { saver.canSaveAutomatically() } // No test clock advance: revision precedes debounce.
        file.writeHook = null
        AutoBackup.onLeaving(app)
        finishSaves(watching = true)
        assertEquals(1, serviceStarts())
        assertNull(store.disk!!.error)
        assertEquals(0, holds())
    }

    @Test fun `empty pending data stops repeated service starts and edits resume repair`() = runBlocking {
        db.insertCategory("Home")
        saver.enable(folder)
        file.failure = "open"
        saver.save(true)
        file.failure = null
        TaskRepository(db).replaceAll(emptyList(), emptyList())
        val pending = store.disk
        AutoBackup.onLeaving(app) // Conservative until the first empty snapshot is observed.
        finishSaves()
        assertEquals(1, serviceStarts())
        repeat(3) {
            AutoBackup.onLeaving(app)
            finishSaves()
            assertEquals(0, serviceStarts())
            assertEquals(pending, store.disk)
        }
        AutoBackup.onForeground(app) // Start the invalidation watcher.
        finishSaves(watching = true)
        db.insertCategory("New data")
        // Room delivers invalidations on its own executor; wait for the watcher to collect them.
        delay(200)
        scheduler.runCurrent()
        scheduler.advanceTimeBy(1_001)
        scheduler.runCurrent()
        AutoBackup.onLeaving(app)
        finishSaves(watching = true)
        assertEquals(1, serviceStarts())
        assertFalse(store.disk!!.pending)
        assertNull(store.disk!!.error)
        assertEquals(3, file.writes.size)
    }

    @Test fun `process reset waits for an existing save before database teardown`() = runBlocking {
        db.insertCategory("Home")
        saver.enable(folder)
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        file.writeHook = { entered.countDown(); check(finish.await(10, TimeUnit.SECONDS)) }
        AutoBackup.backUpNow(app)
        scheduler.runCurrent()
        check(entered.await(10, TimeUnit.SECONDS))
        val reset = async(Dispatchers.Default) { AutoBackup.resetProcessForTesting() }
        try {
            delay(100)
            assertFalse(reset.isCompleted)
            assertTrue(db.isOpen)
            assertTrue(saver.status.value.saving)
        } finally { finish.countDown() }
        pumpUntil { reset.isCompleted }
        reset.await()
        assertFalse(saver.status.value.saving)
        assertFalse(store.disk!!.pending)
        assertEquals(3, file.writes.size)
    }

    @Test fun `note-only add edit and delete trigger the automatic backup watcher`() = runBlocking {
        val category = db.insertCategory("Home")
        val repo = TaskRepository(db)
        val id = repo.createTask(com.taskfree.app.domain.model.TaskInput("Task", null,
            com.taskfree.app.domain.model.Recurrence.NONE, category))
        saver.enable(folder)
        AutoBackup.onForeground(app)
        finishSaves(watching = true)
        suspend fun awaitBackupAfter(change: suspend () -> Unit) {
            val before = file.writes.size
            change()
            // Room invalidation delivery uses a real executor, separately from the debounce clock.
            delay(200)
            scheduler.runCurrent()
            scheduler.advanceTimeBy(1_001)
            pumpUntil { saveScope.coroutineContext[Job]!!.children.count() == 1 }
            scheduler.advanceTimeBy(60_000)
            pumpUntil { file.writes.size > before }
            finishSaves(watching = true)
            assertEquals(before + 1, file.writes.size)
        }
        var note = 0
        awaitBackupAfter { note = repo.addNote(id, "Added")!!.id }
        awaitBackupAfter { repo.editNote(note, "Edited") }
        awaitBackupAfter { repo.deleteNote(note) }
    }
}
