// TaskStatus.kt
package com.taskfree.app.domain.model

enum class TaskStatus {
    TODO, PENDING, IN_PROGRESS, DONE;

    companion object {
        fun from(name: String): TaskStatus =
            entries.find { it.name == name }
                ?: throw IllegalArgumentException("Invalid TaskStatus: $name")
    }
}

