// TaskInput.kt
package com.taskfree.app.domain.model

import java.time.Instant
import java.time.LocalDate


data class TaskInput(
    val title: String,
    val dueDate: LocalDate?,
    val recurrence: Recurrence,
    val categoryId: Int,
    val reminderTime: Instant? = null
)
