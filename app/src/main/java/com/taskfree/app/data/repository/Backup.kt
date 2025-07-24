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
