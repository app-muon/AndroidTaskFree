package com.taskfree.app.data.backup

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskfree.app.testutil.assertFails
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
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
    private fun saf(failToOpen: Set<String> = emptySet(), readOnly: Boolean = false): SafBackupFile {
        target = tmp.newFile().apply { writeBytes(ByteArray(100) { 'x'.code.toByte() }) }
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        return SafBackupFile(resolver) { _, mode ->
            modes += mode
            if (mode in failToOpen) throw FileNotFoundException()
            val flags = when {
                readOnly -> ParcelFileDescriptor.MODE_READ_ONLY
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
}
