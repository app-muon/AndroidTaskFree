package com.taskfree.app.data.repository

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import com.taskfree.app.BuildConfig
import com.taskfree.app.R
import com.taskfree.app.data.serialization.InstantSerializer
import com.taskfree.app.data.serialization.LocalDateSerializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import java.time.Instant

object BackupManager {

    private val json = Json {
        encodeDefaults = true
        serializersModule = SerializersModule {
            contextual(LocalDateSerializer)
            contextual(InstantSerializer)
        }
    }

    suspend fun buildJson(
        catRepo: CategoryRepository, taskRepo: TaskRepository
    ): ByteArray = json.encodeToString(
        Backup.serializer(), Backup(
            app_version = BuildConfig.VERSION_NAME,
            exported_at = Instant.now().toString(),
            categories = catRepo.snapshot(),
            tasks = taskRepo.snapshot()
        )
    ).toByteArray()

    suspend fun import(
        ctx: Context,
        uri: Uri,
        taskRepo: TaskRepository
    ) = withContext(Dispatchers.IO) {

        // — 1. Read & parse —
        val backup: Backup = ctx.contentResolver.openInputStream(uri)?.use { stream ->
            json.decodeFromString(Backup.serializer(), stream.bufferedReader().readText())
        } ?: error("Cannot open backup file")

        // — 2. Validate —
        validate(backup)                      // throws if anything is wrong

        // — 3. Atomically replace all data —
        taskRepo.replaceAll(backup.categories, backup.tasks)
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

        val taskIds = b.tasks.map { it.id }
        requireUnique(taskIds) { BackupValidationException(R.string.err_task_duplicate_id) }
        b.tasks.forEach {
            if (it.text.isBlank())
                throw BackupValidationException(R.string.err_task_empty_text, it.id)
            if (it.categoryId !in catIds)
                throw BackupValidationException(
                    R.string.err_task_bad_category, it.id, it.categoryId
                )
        }
    }

    private inline fun requireUnique(list: List<Int>, error: () -> BackupValidationException) {
        if (list.toSet().size != list.size) throw error()
    }
}
