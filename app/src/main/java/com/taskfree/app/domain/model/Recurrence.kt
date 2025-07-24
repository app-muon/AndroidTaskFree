// Recurrence
package com.taskfree.app.domain.model

enum class Recurrence {
    NONE, DAILY, WEEKLY, WEEKENDS, WEEKDAYS, MONTHLY;

    companion object {
        fun from(name: String): Recurrence =
            entries.find { it.name == name }
                ?: throw IllegalArgumentException("Invalid Recurrence: $name")
    }
}

