package com.taskfree.app.data.backup

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskfree.app.Prefs
import com.taskfree.app.data.backup.BackupError.Kind
import com.taskfree.app.data.database.AppDatabase
import com.taskfree.app.testutil.inMemoryDb
import com.taskfree.app.testutil.insertCategory
import com.taskfree.app.testutil.insertTask
import com.taskfree.app.testutil.task
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class BackupSaverTest {

    private class FakeFile : BackupFile {
        var writable = true
        var failure: String? = null
        val writes = mutableListOf<ByteArray>()

        override fun canWrite(uri: Uri) = writable

        override fun write(uri: Uri, bytes: ByteArray) {
            failure?.let { throw BackupWriteException(it) }
            writes += bytes
        }
    }

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val phrase = listOf("apple", "brick", "cloud", "delta", "eagle", "flame", "grape", "house")
    private val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ATaskFree.tfbackup")
    private val now = Instant.parse("2026-10-10T12:00:00Z")
    private val file = FakeFile()
    private lateinit var db: AppDatabase
    private lateinit var saver: BackupSaver

    private val settings get() = saver.status.value.settings!!

    @Before
    fun setUp() {
        db = inMemoryDb()
        Prefs.savePhrase(ctx, phrase)
        saver = BackupSaver(ctx, file, database = { db }, now = { now }, iterations = 1_000)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun enableWithData(): Int {
        val category = db.insertCategory("Home")
        saver.enable(uri, "TaskFree.tfbackup")
        return category
    }

    private fun lastWrittenText(with: List<String> = phrase) =
        BackupCrypto.decrypt(file.writes.last(), with)!!.decodeToString()

    @Test
    fun `saves an encrypted copy and skips saves when nothing changed`() = runTest {
        val category = enableWithData()

        saver.save(force = false)
        assertEquals(1, file.writes.size)
        assertTrue("\"Home\"" in lastWrittenText())
        assertEquals(now, settings.savedAt)
        assertNull(settings.error)

        saver.save(force = false)
        assertEquals(1, file.writes.size)
        saver.save(force = true)
        assertEquals(2, file.writes.size)

        db.insertTask(task(category, "Water plants"))
        saver.save(force = false)
        assertEquals(3, file.writes.size)
        assertTrue("Water plants" in lastWrittenText())
    }

    @Test
    fun `a failed write is recorded and the next save retries`() = runTest {
        enableWithData()
        file.failure = "Failed at write rwt: IOException"

        saver.save(force = false)

        assertEquals(BackupError(Kind.FAILED, now, "Failed at write rwt: IOException"), settings.error)
        assertNull(settings.fingerprint)
        assertNull(settings.savedAt)

        file.failure = null
        saver.save(force = false)

        assertEquals(1, file.writes.size)
        assertNull(settings.error)
        assertNotNull(settings.fingerprint)
    }

    @Test
    fun `lost access and a changed phrase stop saving until the user acts`() = runTest {
        enableWithData()

        file.writable = false
        saver.save(force = true)
        assertEquals(Kind.NO_ACCESS, settings.error?.kind)

        file.writable = true
        Prefs.savePhrase(ctx, phrase.reversed())
        saver.save(force = true)
        assertEquals(Kind.PHRASE_CHANGED, settings.error?.kind)

        Prefs.clearEncryptionSecrets(ctx)
        saver.save(force = true)
        assertEquals(Kind.PHRASE_CHANGED, settings.error?.kind)
        assertTrue(file.writes.isEmpty())

        Prefs.savePhrase(ctx, phrase.reversed())
        saver.usePhrase()
        saver.save(force = false)

        assertEquals(1, file.writes.size)
        assertNull(settings.error)
        assertTrue("\"Home\"" in lastWrittenText(with = phrase.reversed()))
    }

    @Test
    fun `an empty database is saved only when forced`() = runTest {
        saver.enable(uri, "TaskFree.tfbackup")

        saver.save(force = false)
        assertTrue(file.writes.isEmpty())
        assertNull(settings.error)

        saver.save(force = true)
        assertEquals(1, file.writes.size)
    }

    @Test
    fun `unsaved changes follow the data`() = runTest {
        assertFalse(saver.hasUnsavedChanges())
        val category = enableWithData()
        assertTrue(saver.hasUnsavedChanges())

        saver.save(force = false)
        assertFalse(saver.hasUnsavedChanges())

        db.insertTask(task(category, "Water plants"))
        assertTrue(saver.hasUnsavedChanges())
    }

    @Test
    fun `turning off forgets the file and stops saving`() = runTest {
        enableWithData()
        saver.save(force = false)

        saver.disable()
        saver.save(force = true)

        assertNull(saver.status.value.settings)
        assertFalse(saver.isEnabled)
        assertEquals(1, file.writes.size)
        assertNull(BackupSaver(ctx, file, database = { db }).status.value.settings)
    }
}
