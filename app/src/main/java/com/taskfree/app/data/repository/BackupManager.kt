package com.taskfree.app.data.repository

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import com.taskfree.app.BuildConfig
import com.taskfree.app.R
import com.taskfree.app.data.backup.BackupCrypto
import com.taskfree.app.data.serialization.InstantSerializer
import com.taskfree.app.data.serialization.LocalDateSerializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant

object BackupManager {

    internal const val MAX_FILE_BYTES = 20 * 1024 * 1024
    // Some editors start JSON files with one.
    private val BYTE_ORDER_MARK = Char(0xFEFF).toString()

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(LocalDateSerializer)
            contextual(InstantSerializer)
        }
    }

    suspend fun snapshot(catRepo: CategoryRepository, taskRepo: TaskRepository): Backup = Backup(
        app_version = BuildConfig.VERSION_NAME,
        exported_at = Instant.now().toString(),
        categories = catRepo.snapshot().sortedBy { it.id },
        tasks = taskRepo.snapshot().sortedBy { it.id }
    )

    fun encode(backup: Backup): ByteArray =
        json.encodeToString(Backup.serializer(), backup).toByteArray()

    suspend fun buildJson(catRepo: CategoryRepository, taskRepo: TaskRepository): ByteArray =
        encode(snapshot(catRepo, taskRepo))

    /** Reads a backup file once. A plain file is parsed and validated now; an encrypted one by [unlock]. */
    suspend fun read(ctx: Context, uri: Uri): BackupSource = withContext(Dispatchers.IO) {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readLimited() }
            ?: error("Cannot open backup file")
        if (BackupCrypto.isEncrypted(bytes)) {
            BackupCrypto.checkHeader(bytes)
            BackupSource.Encrypted(bytes)
        } else BackupSource.Plain(parse(bytes))
    }

    /** Returns null when [phrase] does not unlock the backup. */
    suspend fun unlock(source: BackupSource.Encrypted, phrase: List<String>): Backup? =
        withContext(Dispatchers.Default) { BackupCrypto.decrypt(source.bytes, phrase)?.let(::parse) }

    /** Atomically replaces all data. */
    suspend fun restore(backup: Backup, taskRepo: TaskRepository) =
        taskRepo.replaceAll(backup.categories, backup.tasks)

    private fun parse(bytes: ByteArray): Backup {
        val text = bytes.decodeToString().removePrefix(BYTE_ORDER_MARK)
        if (!text.trimStart().startsWith("{")) throw BackupValidationException(R.string.err_not_a_backup)
        val backup = try {
            json.decodeFromString(Backup.serializer(), text)
        } catch (e: Exception) {
            throw BackupValidationException(R.string.err_backup_damaged).apply { initCause(e) }
        }
        validate(backup)
        return backup
    }

    private fun InputStream.readLimited(): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = read(buffer)
            if (count < 0) return out.toByteArray()
            out.write(buffer, 0, count)
            if (out.size() > MAX_FILE_BYTES) throw BackupValidationException(R.string.err_backup_too_large)
        }
    }


    /* ---------- quick validator ---------- */
    class BackupValidationException(@StringRes val resId: Int, vararg val args: Any)
        : Exception()

    private fun validate(b: Backup) {
        if (b.version != "1.0")
            throw BackupValidationException(R.string.err_backup_version)

        val catIds = b.categories.map { it.id }
        requireUnique(catIds) { BackupValidationException(R.string.err_cat_duplicate_id) }
        if (catIds.any { it <= 0 })
            throw BackupValidationException(R.string.err_cat_bad_id)
        if (b.categories.any { it.title.isBlank() })
            throw BackupValidationException(R.string.err_cat_empty_title)
        val deletedCatIds = b.categories.asSequence()
            .filter { it.isDeleted }
            .map { it.id }
            .toSet()

        val taskIds = b.tasks.map { it.id }
        // Validate IDs before links: Room treats zero as an auto-generated ID.
        (taskIds + b.tasks.mapNotNull { it.sourceTaskId }).firstOrNull { it <= 0 }?.let {
            throw BackupValidationException(R.string.err_task_bad_id, it)
        }
        requireUnique(taskIds) { BackupValidationException(R.string.err_task_duplicate_id) }
        b.tasks.forEach {
            if (it.text.isBlank())
                throw BackupValidationException(R.string.err_task_empty_text, it.id)
            if (it.categoryId !in catIds)
                throw BackupValidationException(
                    R.string.err_task_bad_category, it.id, it.categoryId
                )
            if (!it.isArchived && it.categoryId in deletedCatIds)
                throw BackupValidationException(
                    R.string.err_task_live_deleted_category, it.id, it.categoryId
                )
        }

        val byId = b.tasks.associateBy { it.id }
        val sources = b.tasks.mapNotNull { it.sourceTaskId }
        requireUnique(sources) { BackupValidationException(R.string.err_task_occurrence_links) }
        if (sources.any { it !in byId })
            throw BackupValidationException(R.string.err_task_occurrence_links)
        val checked = mutableSetOf<Int>()
        for (task in b.tasks) {
            val path = mutableSetOf<Int>()
            var currentId: Int? = task.id
            while (currentId != null && currentId !in checked) {
                if (!path.add(currentId))
                    throw BackupValidationException(R.string.err_task_occurrence_links)
                currentId = byId.getValue(currentId).sourceTaskId
            }
            checked.addAll(path)
        }
    }

    private inline fun requireUnique(list: List<Int>, error: () -> BackupValidationException) {
        if (list.toSet().size != list.size) throw error()
    }
}
