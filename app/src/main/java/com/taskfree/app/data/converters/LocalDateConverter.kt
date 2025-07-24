// LocalDateConverter.kt
package com.taskfree.app.data.converters

import androidx.room.TypeConverter
import java.time.LocalDate

class LocalDateConverter {
    @TypeConverter
    fun toEpochDay(date: LocalDate?): Long? = date?.toEpochDay()

    @TypeConverter
    fun fromEpochDay(epoch: Long?): LocalDate? = epoch?.let(LocalDate::ofEpochDay)
}
