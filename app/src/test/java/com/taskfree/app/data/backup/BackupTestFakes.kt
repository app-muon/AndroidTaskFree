package com.taskfree.app.data.backup

import android.net.Uri

internal class BackupTestStore : BackupSettingsStore {
    var disk: BackupSettings? = null
    var failAfter: Int? = null
    var persistFailedCommit = false
    var commits = 0
    override fun load() = disk
    override fun commit(settings: BackupSettings?): Boolean {
        commits++
        failAfter = failAfter?.minus(1)
        if (failAfter == 0) {
            if (persistFailedCommit) disk = settings
            return false
        }
        disk = settings
        return true
    }
}

internal class BackupTestFile : BackupFile {
    var writable = true
    var failure: String? = null
    var writeHook: ((Uri) -> Unit)? = null
    var verifiedHook: ((Uri) -> Unit)? = null
    var verifyHook: ((Uri) -> Unit)? = null
    var createHook: ((Uri, String) -> BackupSlot?)? = null
    var failCreation: Int? = null
    var creations = 0
    var failCleanup = false
    var retainFailure: Exception? = null
    var lastWrite: Uri? = null
    val documents = mutableMapOf<Uri, ByteArray>()
    val grants = mutableSetOf<Uri>()
    val writes = mutableListOf<Uri>()
    val deleted = mutableListOf<Uri>()
    override fun retain(uri: Uri) { retainFailure?.let { throw it }; grants += uri }
    override fun release(uri: Uri) { if (failCleanup) error("release"); grants -= uri }
    override fun delete(uri: Uri) {
        deleted += uri
        if (failCleanup) error("delete")
        documents.remove(uri)
    }
    override fun canWrite(uri: Uri) = writable && uri in grants
    override fun create(folder: Uri, name: String): BackupSlot {
        if (++creations == failCreation) throw BackupWriteException("create")
        createHook?.invoke(folder, name)?.let { return it }
        var suffix = 0
        var uri = Uri.parse("$folder/$name")
        while (uri in documents) { uri = Uri.parse("$folder/${++suffix}-$name") }
        documents[uri] = byteArrayOf()
        return BackupSlot(uri, uri.lastPathSegment!!)
    }
    override fun write(uri: Uri, bytes: ByteArray) {
        writeHook?.invoke(uri)
        if (failure == "open") throw BackupWriteException("open")
        writes += uri
        lastWrite = uri
        documents[uri] = if (failure == "partial write") bytes.copyOf(bytes.size / 2) else bytes.copyOf()
        if (failure != null && failure != "verification") throw BackupWriteException(failure!!)
    }
    override fun verify(uri: Uri, size: Long, digest: String) {
        verifyHook?.invoke(uri)
        if (failure == "verification" && uri == lastWrite) throw BackupWriteException("verification")
        val bytes = documents[uri] ?: throw java.io.FileNotFoundException()
        if (bytes.size.toLong() != size || bytes.backupDigest() != digest) throw BackupVerificationMismatchException()
        verifiedHook?.invoke(uri)
    }
}

