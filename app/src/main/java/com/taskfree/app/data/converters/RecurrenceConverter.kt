package com.taskfree.app.data.converters

import androidx.room.TypeConverter
import com.taskfree.app.domain.model.Recurrence

class RecurrenceConverter {
    @TypeConverter
    fun fromRecurrence(recurrence: Recurrence): String = recurrence.name

    @TypeConverter
    fun toRecurrence(name: String): Recurrence = Recurrence.from(name)
}
