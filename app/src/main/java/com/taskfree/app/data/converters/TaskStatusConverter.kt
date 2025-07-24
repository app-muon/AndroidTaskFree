// TaskStatusConverter.kt
package com.taskfree.app.data.converters

import androidx.room.TypeConverter
import com.taskfree.app.domain.model.TaskStatus

class TaskStatusConverter {
    @TypeConverter
    fun fromStatus(status: TaskStatus): String = status.name

    @TypeConverter
    fun toStatus(name: String): TaskStatus = TaskStatus.from(name)
}