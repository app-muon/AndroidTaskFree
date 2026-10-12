package com.taskfree.app.data.backup

import android.content.Context
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.os.Bundle
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskfree.app.testutil.assertFails
import com.taskfree.app.data.repository.BackupManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class SafBackupFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val uri = Uri.parse("content://test/backup")
    private val bytes = "new backup".toByteArray()
    private val modes = mutableListOf<String>()
    private lateinit var target: File

    /** Opens [target] like a provider would; plain "w" deliberately does not truncate. */
    private fun saf(failToOpen: Set<String> = emptySet(), readOnly: Boolean = false,
                    syncError: Int? = null): SafBackupFile {
        target = tmp.newFile().apply { writeBytes(ByteArray(100) { 'x'.code.toByte() }) }
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        return SafBackupFile(resolver, sync = { syncError?.let { throw ErrnoException("fsync", it) } }) { _, mode ->
            modes += mode
            if (mode in failToOpen) throw FileNotFoundException()
            val flags = when {
                readOnly || mode == "r" -> ParcelFileDescriptor.MODE_READ_ONLY
                mode == "w" -> ParcelFileDescriptor.MODE_WRITE_ONLY
                else -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE
            }
            ParcelFileDescriptor.open(target, flags)
        }
    }

    @Test
    fun `a truncating mode is used first`() {
        saf().write(uri, bytes)

        assertEquals(listOf("rwt"), modes)
        assertArrayEquals(bytes, target.readBytes())
    }

    @Test
    fun `only failing to open falls back, and plain w drops old bytes`() {
        saf(failToOpen = setOf("rwt", "wt")).write(uri, bytes)

        assertEquals(listOf("rwt", "wt", "w"), modes)
        assertArrayEquals(bytes, target.readBytes())
    }

    @Test
    fun `a failed write does not fall back to another mode`() {
        val e = assertFails<BackupWriteException> { saf(readOnly = true).write(uri, bytes) }

        assertEquals(listOf("rwt"), modes)
        assertTrue(e.detail, e.detail.startsWith("Failed at write rwt: "))
    }

    @Test
    fun `failing to open in every mode reports the last attempt`() {
        val e = assertFails<BackupWriteException> { saf(failToOpen = setOf("rwt", "wt", "w")).write(uri, bytes) }

        assertEquals("Failed at open w: FileNotFoundException", e.detail)
    }

    @Test
    fun `failure details name exception types and errno, never messages`() {
        val e = IOException("/storage/emulated/0/Download/secret.tfbackup", ErrnoException("write", OsConstants.EBADF))

        assertEquals("Failed at write rwt: IOException / ErrnoException EBADF", failureDetail("write rwt", e))
    }

    @Test
    fun `verification requires exact size and digest after close`() {
        val file = saf()
        file.write(uri, bytes)
        file.verify(uri, bytes.size.toLong(), bytes.backupDigest())
        for (bad in listOf(bytes + byteArrayOf(1), bytes.copyOf(bytes.size - 1), ByteArray(bytes.size))) {
            target.writeBytes(bad)
            assertFails<BackupVerificationMismatchException> { file.verify(uri, bytes.size.toLong(), bytes.backupDigest()) }
        }
    }

    @Test
    fun `genuine sync failures fail saves while unsupported sync still requires verification`() {
        val error = assertFails<BackupWriteException> { saf(syncError = OsConstants.EIO).write(uri, bytes) }
        assertEquals("Failed at sync: ErrnoException EIO", error.detail)
        val file = saf(syncError = OsConstants.EINVAL)
        file.write(uri, bytes)
        file.verify(uri, bytes.size.toLong(), bytes.backupDigest())
        val unreadable = saf(failToOpen = setOf("r"))
        unreadable.write(uri, bytes)
        assertFails<BackupWriteException> { unreadable.verify(uri, bytes.size.toLong(), bytes.backupDigest()) }
    }

    @Test
    fun `verification preserves permission and temporary read causes without calling them mismatches`() {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        for (failure in listOf(SecurityException(), FileNotFoundException(), IOException())) {
            val file = SafBackupFile(resolver, open = { _, _ -> throw failure })
            val error = assertFails<BackupWriteException> { file.verify(uri, bytes.size.toLong(), bytes.backupDigest()) }
            assertEquals(failure, error.cause)
        }
    }

    @Test
    fun `display name query failure returns the created document and requested name`() {
        val created = Uri.parse("content://test/document/new")
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
                if (method == "android:createDocument") return Bundle().apply { putParcelable("uri", created) }
                throw SecurityException("Provider refuses deletion")
            }
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                               selectionArgs: Array<out String>?, sortOrder: String?): Cursor = throw IOException()
            override fun getType(uri: Uri) = "application/octet-stream"
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        }
        provider.attachInfo(ApplicationProvider.getApplicationContext(), android.content.pm.ProviderInfo().apply { authority = "test" })
        ShadowContentResolver.registerProviderInternal("test", provider)
        val file = SafBackupFile(ApplicationProvider.getApplicationContext<Context>().contentResolver)
        val slot = file.create(Uri.parse("content://test/tree/root"), "Requested.tfbackup")
        assertEquals(created, slot.uri)
        assertEquals("Requested.tfbackup", slot.fileName)
        file.delete(slot.uri) // A provider's cleanup refusal is best effort, never another setup error.
    }

    @Test
    fun `oversized output is rejected before opening or changing the destination`() {
        val file = saf()
        val original = target.readBytes()
        assertFails<BackupManager.BackupValidationException> {
            file.write(uri, ByteArray(BackupManager.MAX_FILE_BYTES + 1))
        }
        assertTrue(modes.isEmpty())
        assertArrayEquals(original, target.readBytes())
    }
}
