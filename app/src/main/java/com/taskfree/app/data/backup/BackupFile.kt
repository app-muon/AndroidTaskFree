package com.taskfree.app.data.backup

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.OsConstants
import java.io.FileNotFoundException

/** The file the backup is written to. */
internal interface BackupFile {
    /** True while the persisted write grant for [uri] is still held. */
    fun canWrite(uri: Uri): Boolean

    /** Replaces the file's contents; throws [BackupWriteException] on failure. */
    fun write(uri: Uri, bytes: ByteArray)
}

/** [detail] is safe to show: it names the failed step and exception types, never messages or paths. */
internal class BackupWriteException(val detail: String) : Exception()

/** For example "Failed at write rwt: IOException / ErrnoException EBADF". */
internal fun failureDetail(step: String, e: Throwable): String =
    "Failed at $step: " + generateSequence(e) { it.cause }.take(5).joinToString(" / ") {
        if (it is ErrnoException) "ErrnoException ${OsConstants.errnoName(it.errno) ?: it.errno}"
        else it.javaClass.simpleName
    }

/** Writes through the Storage Access Framework, following encrypted_backup_tips.md. */
internal class SafBackupFile(
    private val resolver: ContentResolver,
    private val open: (Uri, String) -> ParcelFileDescriptor? = { uri, mode -> resolver.openFileDescriptor(uri, mode) }
) : BackupFile {

    override fun canWrite(uri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }

    override fun write(uri: Uri, bytes: ByteArray) {
        val (mode, descriptor) = openForWrite(uri)
        var step = "write $mode"
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { out ->
                out.write(bytes)
                out.flush()
                runCatching { out.fd.sync() } // Not every provider's descriptor supports sync.
                if (mode == "w") {
                    // Plain "w" may keep old bytes past the new end. Only this mode is checked:
                    // after a truncating mode a cloud provider can report the old size for a while.
                    step = "size check"
                    out.channel.truncate(bytes.size.toLong())
                    val size = descriptor.statSize
                    if (size != -1L && size != bytes.size.toLong())
                        throw BackupWriteException("Failed at size check: $size bytes, expected ${bytes.size}")
                }
                step = "close"
            }
        } catch (e: BackupWriteException) {
            throw e
        } catch (e: Exception) {
            throw BackupWriteException(failureDetail(step, e))
        }
    }

    /** Truncating modes first. Only a failure to open falls back; a failed write never does. */
    private fun openForWrite(uri: Uri): Pair<String, ParcelFileDescriptor> {
        var failure: Exception? = null
        for (mode in listOf("rwt", "wt", "w")) {
            try {
                return mode to (open(uri, mode) ?: throw FileNotFoundException())
            } catch (e: Exception) {
                failure = e
            }
        }
        throw BackupWriteException(failureDetail("open w", failure!!))
    }
}
