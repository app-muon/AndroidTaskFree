package com.taskfree.app.domain.model

import java.time.Instant
import java.time.LocalDate

data class TaskInput(
    val title: String,
    val dueDate: LocalDate?,
    val repeatRule: RepeatRule,
    val categoryId: Int,
    val reminderTime: Instant? = null
)
