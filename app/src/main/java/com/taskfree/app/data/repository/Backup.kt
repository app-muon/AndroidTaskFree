// Backup.kt
package com.taskfree.app.data.repository

import com.taskfree.app.data.entities.Category
import com.taskfree.app.data.entities.Task
import kotlinx.serialization.Serializable

@Serializable
data class Backup(
    val version: String = "1.0",
    val app_version: String,
    val exported_at: String,
    val categories: List<Category>,
    val tasks: List<Task>
)

/** A backup file as read from disk: a plain one is already parsed; an encrypted one needs its phrase. */
sealed interface BackupSource {
    data class Plain(val backup: Backup) : BackupSource
    class Encrypted internal constructor(internal val bytes: ByteArray) : BackupSource
}
