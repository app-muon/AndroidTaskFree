package com.taskfree.app.data.backup

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskfree.app.Prefs
import com.taskfree.app.data.backup.BackupError.Kind
import com.taskfree.app.data.database.AppDatabase
import com.taskfree.app.data.repository.BackupManager
import com.taskfree.app.data.repository.BackupSource
import com.taskfree.app.data.repository.TaskRepository
import com.taskfree.app.testutil.inMemoryDb
import com.taskfree.app.testutil.insertCategory
import com.taskfree.app.testutil.insertTask
import com.taskfree.app.testutil.task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BackupSaverTest {
    @get:Rule val tmp = TemporaryFolder()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val phrase = listOf("apple", "brick", "cloud", "delta", "eagle", "flame", "grape", "house")
    private val folder = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload%2FTaskFree")
    private var time = Instant.parse("2026-10-10T12:00:00Z")
    private val file = BackupTestFile()
    private val store = BackupTestStore()
    private lateinit var db: AppDatabase
    private lateinit var saver: BackupSaver
    private var snapshots = 0
    private val settings get() = saver.status.value.settings!!
    private fun newSaver() = BackupSaver(ctx, file, { snapshots++; db }, { time }, 1_000, store)

    @Before fun setUp() {
        db = inMemoryDb()
        Prefs.savePhrase(ctx, phrase)
        saver = newSaver()
    }
    @After fun tearDown() = db.close()

    private suspend fun enableWithData(): Int {
        val category = db.insertCategory("Home")
        db.insertTask(task(category, "Water plants"))
        saver.enable(folder)
        assertNull(saver.status.value.error)
        assertEquals(2, file.writes.size)
        return category
    }

    private suspend fun assertRestores(uri: Uri, expectedText: String = "Water plants") {
        val source = BackupManager.read(ctx, Uri.fromFile(tmp.newFile().apply { writeBytes(file.documents.getValue(uri)) }))
        val backup = BackupManager.unlock(source as BackupSource.Encrypted, phrase)!!
        val target = inMemoryDb()
        try {
            BackupManager.restore(backup, TaskRepository(target))
            assertEquals("Home", target.categoryDao().getAllNow().single().title)
            assertEquals(expectedText, target.taskDao().getAllNow().single().text)
        } finally { target.close() }
    }

    @Test fun `setup creates two restorable files and saves alternate while unchanged content is skipped`() = runTest {
        enableWithData()
        settings.slots.forEach { assertRestores(it.uri) }
        val first = settings.activeSlot
        saver.save(false)
        assertEquals(2, file.writes.size)
        saver.save(true)
        assertEquals(1 - first, settings.activeSlot)
        saver.save(true)
        assertEquals(first, settings.activeSlot)
        assertFalse(saver.needsAutomaticSave())
        assertEquals(settings, store.load())
    }

    @Test fun `all write failures preserve active data and an unchanged automatic save repairs them`() = runTest {
        enableWithData()
        for (stage in listOf("open", "partial write", "flush", "sync", "close", "verification")) {
            val before = settings
            val protected = before.active!!.uri
            val original = file.documents.getValue(protected).copyOf()
            time = time.plusSeconds(60)
            file.lastWrite = null
            file.failure = stage
            saver.save(true)
            assertEquals(stage, Kind.FAILED, settings.error?.kind)
            assertEquals(before.savedAt, settings.savedAt)
            assertEquals(before.fingerprint, settings.fingerprint)
            assertEquals(before.activeSlot, settings.activeSlot)
            assertArrayEquals(original, file.documents[protected])
            assertRestores(protected)
            assertTrue(saver.needsAutomaticSave())
            file.failure = null
            saver.save(false)
            assertNull(settings.error)
            assertFalse(settings.pending)
            assertEquals(time, settings.savedAt)
            assertEquals(1 - before.activeSlot, settings.activeSlot)
            settings.slots.forEach { assertRestores(it.uri) }
        }
    }

    @Test fun `damaged protected file pauses without modifying the remaining file`() = runTest {
        enableWithData()
        val active = settings.active!!.uri
        val remaining = settings.slots[1 - settings.activeSlot].uri
        val intact = file.documents.getValue(remaining).copyOf()
        for (bad in listOf(byteArrayOf(1), file.documents.getValue(active).map { (it + 1).toByte() }.toByteArray())) {
            file.documents[active] = bad
            saver.save(true)
            assertEquals(Kind.ACTIVE_DAMAGED, settings.error?.kind)
            assertEquals(2, file.writes.size)
            assertArrayEquals(intact, file.documents[remaining])
            assertRestores(remaining)
        }
    }

    @Test fun `missing protected file stays retryable without opening survivor until explicit pair recovery`() = runTest {
        enableWithData()
        val before = settings
        val protected = before.active!!.uri
        val survivor = before.slots[1 - before.activeSlot].uri
        val intact = file.documents.getValue(survivor).copyOf()
        file.documents.remove(protected)
        repeat(3) {
            saver.save(it == 0)
            assertEquals(Kind.FAILED, settings.error?.kind)
            assertTrue(saver.canSaveAutomatically())
            assertTrue(saver.needsAutomaticSave())
            assertEquals(2, file.writes.size)
            assertArrayEquals(intact, file.documents[survivor])
            assertEquals(before.savedAt, settings.savedAt)
        }
        time = time.plusSeconds(60)
        saver.enable(folder)
        assertNull(settings.error)
        assertNull(saver.status.value.operationError)
        assertTrue(settings.slots.none { it.uri in before.slots.map { slot -> slot.uri } })
        assertArrayEquals(intact, file.documents[survivor])
        settings.slots.forEach { assertRestores(it.uri) }
    }

    @Test fun `plain and wrapped protected read failures retry safely and recover transiently`() = runTest {
        enableWithData()
        for (failure in listOf(java.io.FileNotFoundException(),
            BackupWriteException("verification", java.io.FileNotFoundException()),
            BackupWriteException("verification", java.io.IOException()))) {
            val before = settings
            val originals = file.documents.mapValues { it.value.copyOf() }
            val writes = file.writes.size
            file.verifyHook = { throw failure }
            repeat(3) {
                saver.save(it == 0)
                assertEquals(Kind.FAILED, settings.error?.kind)
                assertTrue(saver.needsAutomaticSave())
                assertEquals(writes, file.writes.size)
                originals.forEach { (uri, bytes) -> assertArrayEquals(bytes, file.documents[uri]) }
            }
            file.verifyHook = null
            saver.save(false)
            assertNull(settings.error)
            assertEquals(1 - before.activeSlot, settings.activeSlot)
            assertEquals(writes + 1, file.writes.size)
            assertArrayEquals(originals[before.active!!.uri], file.documents[before.active!!.uri])
        }
    }

    @Test fun `pairs share a UTC creation date keep provider names and retain previous pairs during rotation`() = runTest {
        val originalZone = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Auckland"))
            enableWithData()
            val names = listOf("TaskFree-2026-10-10_12-00-00Z-A.tfbackup", "TaskFree-2026-10-10_12-00-00Z-B.tfbackup")
            assertEquals(names, settings.slots.map { it.fileName })
            val originals = file.documents.mapValues { it.value.copyOf() }
            saver.enable(folder) // Same second: provider adds collision suffixes.
            val returnedNames = names.map { "1-$it" }
            assertEquals(returnedNames, settings.slots.map { it.fileName })
            assertEquals(returnedNames, store.load()!!.slots.map { it.fileName })
            time = time.plusSeconds(3600)
            repeat(3) { saver.save(true) }
            assertEquals(returnedNames, settings.slots.map { it.fileName })
            assertEquals(4, file.documents.size)
            originals.forEach { (uri, bytes) -> assertArrayEquals(bytes, file.documents[uri]) }
            // Configured older names are reused too, without creating or renaming documents.
            store.disk = settings.copy(slots = settings.slots.mapIndexed { index, slot ->
                slot.copy(fileName = if (index == 0) "TaskFree-A.tfbackup" else "TaskFree-B.tfbackup")
            })
            saver = newSaver()
            saver.save(true)
            assertEquals(listOf("TaskFree-A.tfbackup", "TaskFree-B.tfbackup"), settings.slots.map { it.fileName })
            assertEquals(4, file.creations)
        } finally { java.util.TimeZone.setDefault(originalZone) }
    }

    @Test fun `failed settings commits freeze the trusted state even if the candidate reached disk`() = runTest {
        enableWithData()
        for (persisted in listOf(false, true)) {
            val before = settings
            store.failAfter = 2 // Pending commit succeeds; success commit fails.
            store.persistFailedCommit = persisted
            saver.save(true)
            assertTrue(saver.status.value.restartRequired)
            assertFalse(saver.status.value.canMutate)
            assertFalse(saver.needsAutomaticSave())
            assertEquals(before.activeSlot, settings.activeSlot)
            assertEquals(before.savedAt, settings.savedAt)
            val writes = file.writes.size
            saver.save(true)
            saver.enable(Uri.parse("content://new/folder"))
            saver.disable()
            saver.usePhrase()
            assertEquals(writes, file.writes.size)
            assertRestores(before.active!!.uri)
            assertTrue(folder in file.grants)
            store.failAfter = null
            saver = newSaver() // Represents a fresh process reading the durable copy.
            saver.save(true)
            assertNull(saver.status.value.error)
            settings.slots.forEach { assertRestores(it.uri) }
        }
    }

    @Test fun `pending commit failure happens before any destination is opened`() = runTest {
        enableWithData()
        store.failAfter = 1
        saver.save(true)
        assertEquals(2, file.writes.size)
        assertTrue(saver.status.value.restartRequired)
    }

    private class ProcessDeath : Error()

    @Test fun `restart before write after verification and after commitment preserves a complete backup`() = runTest {
        enableWithData()
        for (afterVerification in listOf(false, true)) {
            val before = settings
            val inactive = before.slots[1 - before.activeSlot].uri
            if (afterVerification) file.verifiedHook = { if (it == inactive) throw ProcessDeath() }
            else file.writeHook = { throw ProcessDeath() }
            try { saver.save(true); fail("Expected simulated interruption") } catch (_: ProcessDeath) { }
            assertTrue(store.disk!!.pending)
            assertRestores(before.active!!.uri)
            file.writeHook = null
            file.verifiedHook = null
            saver = newSaver()
            assertTrue(saver.needsAutomaticSave())
            saver.save(false)
            assertFalse(settings.pending)
            assertEquals(1 - before.activeSlot, settings.activeSlot)
            saver = newSaver() // The success commit is durable too.
            assertFalse(saver.needsAutomaticSave())
            settings.slots.forEach { assertRestores(it.uri) }
        }
    }

    @Test fun `folder setup failure and filename collisions preserve old files and configuration`() = runTest {
        enableWithData()
        val old = settings
        val originals = file.documents.mapValues { it.value.copyOf() }
        file.failure = "partial write"
        val newFolder = Uri.parse("content://test/tree/new")
        saver.enable(newFolder)
        assertEquals(old, settings)
        assertNotNull(saver.status.value.operationError)
        assertTrue(folder in file.grants)
        assertFalse(newFolder in file.grants)
        originals.forEach { (uri, bytes) -> assertArrayEquals(bytes, file.documents[uri]) }
        file.failure = null
        saver.enable(folder) // Same names already exist: always create new documents.
        assertTrue(settings.slots.none { it.uri in originals })
        originals.forEach { (uri, bytes) -> assertArrayEquals(bytes, file.documents[uri]) }
        saver.enable(newFolder)
        assertFalse(folder in file.grants)
        assertTrue(newFolder in file.grants)
    }

    @Test fun `legacy single file is paused until successful folder migration`() = runTest {
        val legacy = Uri.parse("content://test/document/old")
        store.disk = BackupSettings(legacy, com.taskfree.app.ui.enc.MnemonicManager.hashPhrase(phrase),
            legacyFileName = "TaskFree.tfbackup", savedAt = time, pending = false)
        file.documents[legacy] = byteArrayOf(7, 8, 9)
        file.grants += legacy
        saver = newSaver()
        assertTrue(settings.needsMigration)
        assertFalse(saver.isEnabled)
        saver.save(true)
        assertTrue(file.writes.isEmpty())
        assertFalse(saver.needsAutomaticSave())
        file.createHook = { _, name -> BackupSlot(legacy, "TaskFree.tfbackup").takeIf { name.contains("B.") } }
        saver.enable(folder)
        assertTrue(settings.needsMigration)
        assertEquals(setOf(legacy), file.documents.keys)
        assertFalse(legacy in file.deleted)
        assertTrue(legacy in file.grants)
        assertFalse(folder in file.grants)
        file.createHook = null
        saver.enable(folder)
        assertFalse(settings.needsMigration)
        assertArrayEquals(byteArrayOf(7, 8, 9), file.documents[legacy])
        assertFalse(legacy in file.grants)
    }

    @Test fun `lost grants changed phrases and automatic empty saves do not overwrite either slot`() = runTest {
        enableWithData()
        file.writable = false
        saver.save(true)
        assertEquals(Kind.NO_ACCESS, settings.error?.kind)
        file.writable = true
        Prefs.savePhrase(ctx, phrase.reversed())
        saver.save(true)
        assertEquals(Kind.PHRASE_CHANGED, settings.error?.kind)
        saver.enable(Uri.parse("content://other/folder"))
        assertEquals(folder, settings.uri)
        Prefs.clearEncryptionSecrets(ctx)
        saver.save(true)
        assertEquals(2, file.writes.size)
        Prefs.savePhrase(ctx, phrase.reversed())
        saver.usePhrase()
        assertNull(saver.status.value.error)
        assertNotNull(saver.status.value.operationError)
        assertNotNull(BackupCrypto.decrypt(file.documents.getValue(settings.active!!.uri), phrase.reversed()))
        TaskRepository(db).replaceAll(emptyList(), emptyList())
        saver.save(false)
        assertEquals(3, file.writes.size)
        saver.save(true)
        assertEquals(4, file.writes.size)
    }

    @Test fun `oversized data preserves both slots and retries when data becomes small again`() = runTest {
        val category = enableWithData()
        val before = settings
        val originals = file.documents.mapValues { it.value.copyOf() }
        val large = (1..24).map { db.insertTask(task(category, "x".repeat(900_000))) }
        saver.save(false)
        assertEquals(Kind.TOO_LARGE, settings.error?.kind)
        assertEquals(before.savedAt, settings.savedAt)
        assertEquals(before.fingerprint, settings.fingerprint)
        originals.forEach { (uri, bytes) -> assertArrayEquals(bytes, file.documents[uri]) }
        val reads = snapshots
        val commits = store.commits
        repeat(3) {
            assertFalse(saver.canSaveAutomatically())
            assertFalse(saver.needsAutomaticSave())
            saver.save(false)
        }
        assertEquals(reads, snapshots)
        assertEquals(commits, store.commits)
        saver.save(true) // Manual reassessment is always allowed.
        assertEquals(reads + 1, snapshots)
        saver = newSaver() // A persisted size error gets one reassessment per process.
        assertTrue(saver.needsAutomaticSave())
        saver.save(false)
        assertFalse(saver.needsAutomaticSave())
        large.forEach { db.taskDao().update(db.taskDao().taskById(it)!!.copy(text = "small")) }
        saver.databaseChanged()
        assertTrue(saver.needsAutomaticSave())
        saver.save(false)
        assertNull(settings.error)
        assertFalse(saver.needsAutomaticSave())
    }

    @Test fun `damage and phrase errors pause before snapshots even after invalidation`() = runTest {
        enableWithData()
        for (kind in listOf(Kind.ACTIVE_DAMAGED, Kind.PHRASE_CHANGED)) {
            store.disk = settings.copy(error = BackupError(kind, time), pending = true)
            saver = newSaver()
            val before = settings
            val reads = snapshots
            val commits = store.commits
            saver.databaseChanged()
            repeat(3) {
                assertFalse(saver.canSaveAutomatically())
                assertFalse(saver.needsAutomaticSave())
                saver.save(false)
            }
            assertEquals(reads, snapshots)
            assertEquals(commits, store.commits)
            assertEquals(before, settings)
        }
    }

    @Test fun `missing grants pause snapshots and restored grants allow automatic repair`() = runTest {
        enableWithData()
        file.writable = false
        saver.save(true)
        val reads = snapshots
        repeat(3) { assertFalse(saver.needsAutomaticSave()); saver.save(false) }
        assertEquals(reads, snapshots)
        file.writable = true
        assertTrue(saver.canSaveAutomatically())
        saver.save(false)
        assertNull(settings.error)
        assertFalse(settings.pending)
    }

    @Test fun `revision captured before snapshot preserves edits made during oversized attempt`() = runTest {
        val category = enableWithData()
        val large = (1..24).map { db.insertTask(task(category, "x".repeat(900_000))) }
        // The snapshot already contains the large rows when protected-slot verification runs.
        file.verifyHook = {
            file.verifyHook = null
            kotlinx.coroutines.runBlocking {
                large.forEach { db.taskDao().update(db.taskDao().taskById(it)!!.copy(text = "small")) }
            }
            saver.databaseChanged()
        }
        saver.save(true)
        assertEquals(Kind.TOO_LARGE, settings.error?.kind)
        assertTrue(saver.needsAutomaticSave())
        saver.save(false)
        assertNull(settings.error)
    }

    @Test fun `phrase acceptance clears only phrase errors and preserves legacy or empty pending work`() = runTest {
        enableWithData()
        val good = settings
        TaskRepository(db).replaceAll(emptyList(), emptyList())
        Prefs.savePhrase(ctx, phrase.reversed())
        for (legacy in listOf(false, true)) {
            for (kind in listOf(Kind.PHRASE_CHANGED, Kind.NO_ACCESS, Kind.ACTIVE_DAMAGED)) {
                val error = BackupError(kind, time)
                store.disk = good.copy(slots = if (legacy) emptyList() else good.slots, error = error)
                saver = newSaver()
                file.writable = false
                saver.usePhrase()
                assertEquals(if (kind == Kind.PHRASE_CHANGED) null else error, settings.error)
                assertTrue(settings.pending)
                assertEquals(legacy, settings.needsMigration)
                assertEquals(com.taskfree.app.ui.enc.MnemonicManager.hashPhrase(phrase.reversed()), settings.phraseHash)
                assertEquals(2, file.writes.size)
            }
        }
        file.writable = true
        saver.enable(folder)
        assertFalse(settings.needsMigration)
        assertNull(settings.error)
    }

    @Test fun `new ciphertext mismatch reports verification failure and protects active slot`() = runTest {
        enableWithData()
        val active = settings.active!!.uri
        file.verifyHook = { if (it != active) throw BackupVerificationMismatchException() }
        saver.save(true)
        assertEquals(Kind.FAILED, settings.error?.kind)
        assertTrue(settings.error!!.detail!!.startsWith("Failed at verification:"))
        assertEquals(active, settings.active!!.uri)
        assertRestores(active)
        assertTrue(saver.needsAutomaticSave())
        file.verifyHook = null
        saver.save(false)
        assertNull(settings.error)
    }

    @Test fun `disable waits for a save before releasing its grant and retains both files`() = runTest {
        enableWithData()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        file.writeHook = { entered.countDown(); check(finish.await(10, TimeUnit.SECONDS)) }
        val saving = async(Dispatchers.Default) { saver.save(true) }
        check(entered.await(10, TimeUnit.SECONDS))
        val disabling = async(Dispatchers.Default) { saver.disable() }
        try {
            assertTrue(folder in file.grants)
            assertNotNull(store.disk)
        } finally { finish.countDown() }
        saving.await()
        disabling.await()
        assertNull(saver.status.value.settings)
        assertNull(store.disk)
        assertTrue(file.grants.isEmpty())
        assertEquals(2, file.documents.size)
        assertEquals(3, file.writes.size)
    }

    @Test fun `folder change notice survives skipped and successful saves and settings failure`() = runTest {
        val category = enableWithData()
        file.failure = "partial write"
        saver.enable(Uri.parse("content://other/folder"))
        val notice = saver.status.value.operationError
        assertNotNull(notice)
        assertNull(saver.status.value.error)
        assertFalse(saver.status.value.pending)
        file.failure = null
        val writes = file.writes.size
        val commits = store.commits
        assertFalse(saver.needsAutomaticSave())
        saver.save(false)
        assertEquals(writes, file.writes.size)
        assertEquals(commits, store.commits)
        assertEquals(notice, saver.status.value.operationError)
        db.insertTask(task(category, "New edit"))
        assertTrue(saver.needsAutomaticSave())
        saver.save(false)
        assertEquals(folder, settings.uri)
        assertEquals(writes + 1, file.writes.size)
        assertEquals(notice, saver.status.value.operationError)
        store.failAfter = 1
        saver.save(true)
        assertTrue(saver.status.value.restartRequired)
        assertEquals(notice, saver.status.value.operationError)
        saver = newSaver()
        assertNull(saver.status.value.operationError)
    }

    @Test fun `empty automatic saves preserve repair state while forced saves and new data still work`() = runTest {
        enableWithData()
        file.failure = "open"
        saver.save(true)
        val pending = settings
        TaskRepository(db).replaceAll(emptyList(), emptyList())
        file.failure = null
        file.writable = false // Empty snapshots must not even replace the existing error.
        val commits = store.commits
        repeat(3) {
            assertFalse(saver.needsAutomaticSave())
            saver.save(false)
            assertEquals(pending, settings)
        }
        assertEquals(commits, store.commits)
        assertEquals(2, file.writes.size)
        file.writable = true
        db.insertCategory("New data")
        assertTrue(saver.needsAutomaticSave())
        saver.save(false)
        assertFalse(settings.pending)
        assertEquals(3, file.writes.size)
        TaskRepository(db).replaceAll(emptyList(), emptyList())
        saver.save(true)
        assertEquals(4, file.writes.size)
        assertFalse(settings.pending)
    }

    @Test fun `temporary protected read failures and lost access preserve inactive slot and recover`() = runTest {
        enableWithData()
        for ((failure, kind) in listOf(
            java.io.FileNotFoundException() to Kind.FAILED,
            BackupWriteException("verification", java.io.IOException()) to Kind.FAILED,
            BackupWriteException("verification", SecurityException()) to Kind.NO_ACCESS
        )) {
            val before = settings
            val original = file.documents.mapValues { it.value.copyOf() }
            val writes = file.writes.size
            file.verifyHook = { if (it == before.active!!.uri) throw failure }
            saver.save(true)
            assertEquals(kind, settings.error?.kind)
            assertEquals(writes, file.writes.size)
            original.forEach { (uri, bytes) -> assertArrayEquals(bytes, file.documents[uri]) }
            file.verifyHook = null
            assertTrue(saver.needsAutomaticSave())
            saver.save(false)
            assertEquals(writes + 1, file.writes.size)
            assertNull(settings.error)
        }
    }

    @Test fun `invalid protected metadata never opens inactive slot`() = runTest {
        enableWithData()
        val good = settings
        val invalid = listOf(
            good.copy(activeSlot = -1),
            good.copy(slots = listOf(good.active!!, good.active!!)),
            good.copy(slots = good.slots.map { it.copy(digest = null) }),
            good.copy(slots = good.slots.map { it.copy(digest = "invalid") }),
            good.copy(slots = good.slots.map { it.copy(size = 0) }),
            good.copy(slots = good.slots.map { it.copy(size = BackupManager.MAX_FILE_BYTES.toLong() + 1) })
        )
        for (candidate in invalid) {
            store.disk = candidate
            saver = newSaver()
            saver.save(true)
            assertEquals(Kind.ACTIVE_DAMAGED, settings.error?.kind)
            assertEquals(2, file.writes.size)
        }
    }

    @Test fun `failed setup cleans only newly returned documents before releasing unused grants`() = runTest {
        enableWithData()
        val old = settings
        val originals = file.documents.mapValues { it.value.copyOf() }
        val newFolder = Uri.parse("content://other/folder")
        for (stage in listOf("create A", "create B", "partial write", "verification")) {
            file.failCreation = when (stage) {
                "create A" -> file.creations + 1
                "create B" -> file.creations + 2
                else -> null
            }
            file.failure = stage.takeIf { !it.startsWith("create") }
            saver.enable(newFolder)
            assertEquals(old, settings)
            assertEquals(originals.keys, file.documents.keys)
            assertTrue(file.deleted.none { it in originals })
            assertFalse(newFolder in file.grants)
            assertTrue(folder in file.grants)
            originals.forEach { (uri, bytes) -> assertArrayEquals(bytes, file.documents[uri]) }
        }
        file.failure = null
        file.failCreation = null
        // A broken provider returning a managed URI must never cause its deletion or overwrite.
        file.createHook = { _, name -> old.active.takeIf { name.contains("B.") } }
        saver.enable(folder)
        assertEquals(originals.keys, file.documents.keys)
        assertTrue(file.deleted.none { it in originals })
        assertTrue(folder in file.grants)
    }

    @Test fun `cleanup failure never replaces the setup error`() = runTest {
        file.failCreation = 2
        file.failCleanup = true
        saver.enable(folder)
        assertEquals("create", saver.status.value.operationError?.detail)
        assertEquals(1, file.deleted.size)
        assertNull(saver.status.value.settings)
    }

    @Test fun `setup cancellation cleans new files and keeps the old grant`() = runTest {
        enableWithData()
        val old = settings
        val originals = file.documents.keys.toSet()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        file.writeHook = { entered.countDown(); check(finish.await(10, TimeUnit.SECONDS)) }
        val setup = async(Dispatchers.Default) { saver.enable(folder) }
        try {
            check(entered.await(10, TimeUnit.SECONDS))
            setup.cancel()
        } finally { finish.countDown() }
        setup.join()
        assertEquals(old, settings)
        assertEquals(originals, file.documents.keys)
        assertTrue(folder in file.grants)
        assertFalse(saver.status.value.saving)
    }

    @Test fun `setup commit uncertainty retains candidate documents and both grants`() = runTest {
        enableWithData()
        for (persisted in listOf(false, true)) {
            val old = settings
            val originals = file.documents.keys.toSet()
            val newFolder = Uri.parse("content://other/folder/$persisted")
            store.failAfter = 1
            store.persistFailedCommit = persisted
            saver.enable(newFolder)
            assertTrue(saver.status.value.restartRequired)
            assertEquals(old, settings)
            assertEquals(originals.size + 2, file.documents.size)
            assertTrue(file.deleted.isEmpty())
            assertTrue(old.uri in file.grants)
            assertTrue(newFolder in file.grants)
            assertEquals(if (persisted) newFolder else old.uri, store.disk!!.uri)
            (file.documents.keys - originals).forEach { assertRestores(it) }
            store.failAfter = null
            saver = newSaver()
        }
    }

    @Test fun `fingerprints remain compatible and durable pending skips only the preliminary commit`() = runTest {
        enableWithData()
        val expected = (BackupManager.encode(BackupManager.snapshot(db).copy(exported_at = "")) +
            settings.phraseHash.toByteArray()).backupDigest()
        assertEquals(expected, settings.fingerprint)
        val initialCommits = store.commits
        saver.save(false)
        assertEquals(initialCommits, store.commits)
        saver.save(true)
        assertEquals(initialCommits + 2, store.commits)
        store.disk = settings.copy(pending = true)
        saver = newSaver()
        saver.save(false)
        assertEquals(initialCommits + 3, store.commits)
        assertFalse(settings.pending)
        assertEquals(expected, settings.fingerprint)
    }

    @Test fun `snapshot failures remain retryable and grant errors belong to setup`() = runTest {
        assertFalse(saver.needsAutomaticSave())
        file.retainFailure = SecurityException()
        saver.enable(folder)
        assertEquals(Kind.NO_ACCESS, saver.status.value.operationError?.kind)
        assertNull(saver.status.value.error)
        file.retainFailure = null
        enableWithData()
        val unavailable = BackupSaver(ctx, file, { error("temporarily unavailable") }, prefs = store)
        assertTrue(unavailable.needsAutomaticSave())
    }
}
