package com.taskfree.app.data.backup

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.taskfree.app.data.repository.BackupManager
import java.io.FileDescriptor
import java.io.FileNotFoundException
import java.security.MessageDigest

internal interface BackupFile {
    fun retain(uri: Uri)
    fun release(uri: Uri)
    /** Both persisted read and write access are needed, including for verification. */
    fun canWrite(uri: Uri): Boolean
    /** Always creates a new document; never looks up a file by name. */
    fun create(folder: Uri, name: String): BackupSlot
    /** Best effort: a provider may refuse deletion. Never use this for managed backup slots. */
    fun delete(uri: Uri)
    fun write(uri: Uri, bytes: ByteArray)
    /** Reads at most [size] + 1 bytes and requires an exact length and SHA-256 match. */
    fun verify(uri: Uri, size: Long, digest: String)
}

/** Safe to show: step and exception types, never exception messages or paths. */
internal class BackupWriteException(val detail: String, cause: Throwable? = null) : Exception(cause)

/** Only an observed ciphertext length/digest mismatch proves failed verification. */
internal class BackupVerificationMismatchException : Exception()

internal fun failureDetail(step: String, e: Throwable): String =
    "Failed at $step: " + generateSequence(e) { it.cause }.take(5).joinToString(" / ") {
        if (it is ErrnoException) "ErrnoException ${OsConstants.errnoName(it.errno) ?: it.errno}"
        else it.javaClass.simpleName
    }

internal fun ByteArray.backupDigest(): String =
    MessageDigest.getInstance("SHA-256").digest(this).toBackupHex()

internal fun ByteArray.toBackupHex(): String = joinToString("") { "%02x".format(it) }

internal class SafBackupFile(
    private val resolver: ContentResolver,
    private val sync: (FileDescriptor) -> Unit = { Os.fsync(it) },
    private val open: (Uri, String) -> ParcelFileDescriptor? = { uri, mode -> resolver.openFileDescriptor(uri, mode) }
) : BackupFile {
    private val grants = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    override fun retain(uri: Uri) = resolver.takePersistableUriPermission(uri, grants)

    override fun release(uri: Uri) {
        runCatching { resolver.releasePersistableUriPermission(uri, grants) }
    }

    override fun canWrite(uri: Uri): Boolean = resolver.persistedUriPermissions.any {
        it.uri == uri && it.isWritePermission && it.isReadPermission
    }

    override fun create(folder: Uri, name: String): BackupSlot {
        try {
            val parent = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
            val uri = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name)
                ?: throw FileNotFoundException()
            val actualName = runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull() ?: name
            return BackupSlot(uri, actualName)
        } catch (e: Exception) {
            throw BackupWriteException(failureDetail("create", e), e)
        }
    }

    override fun delete(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }
    }

    override fun write(uri: Uri, bytes: ByteArray) {
        BackupManager.checkOutputSize(bytes.size.toLong())
        val (mode, descriptor) = openForWrite(uri)
        var step = "write $mode"
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { out ->
                out.write(bytes)
                step = "flush"
                out.flush()
                if (mode == "w") {
                    step = "truncate"
                    out.channel.truncate(bytes.size.toLong())
                }
                step = "sync"
                try {
                    sync(out.fd)
                } catch (e: ErrnoException) {
                    // Pipes and some providers don't implement fsync. Actual I/O failures must propagate.
                    if (e.errno != OsConstants.EINVAL && e.errno != OsConstants.ESPIPE &&
                        e.errno != OsConstants.EOPNOTSUPP) throw e
                }
                step = "close"
            }
        } catch (e: Exception) {
            throw BackupWriteException(failureDetail(step, e), e)
        }
    }

    override fun verify(uri: Uri, size: Long, digest: String) {
        try {
            require(size in 1..BackupManager.MAX_FILE_BYTES.toLong())
            val descriptor = open(uri, "r") ?: throw FileNotFoundException()
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val hash = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var count = 0L
                while (true) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), size + 1 - count).toInt())
                    if (read < 0) break
                    count += read
                    if (count > size) throw BackupVerificationMismatchException()
                    hash.update(buffer, 0, read)
                }
                if (count != size || hash.digest().toBackupHex() != digest)
                    throw BackupVerificationMismatchException()
            }
        } catch (e: BackupVerificationMismatchException) {
            throw e
        } catch (e: Exception) {
            throw BackupWriteException(failureDetail("verification", e), e)
        }
    }

    /** Only open failures try another mode. A failed write never does. */
    private fun openForWrite(uri: Uri): Pair<String, ParcelFileDescriptor> {
        var failure: Exception? = null
        for (mode in listOf("rwt", "wt", "w")) {
            try {
                return mode to (open(uri, mode) ?: throw FileNotFoundException())
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                failure = e
            }
        }
        throw BackupWriteException(failureDetail("open w", failure!!), failure)
    }
}
