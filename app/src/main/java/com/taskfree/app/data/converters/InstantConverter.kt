// InstantConverter.kt
package com.taskfree.app.data.converters

import androidx.room.TypeConverter
import java.time.Instant

class InstantConverter {
    @TypeConverter
    fun toEpochMillis(instant: Instant?): Long? = instant?.toEpochMilli()

    @TypeConverter
    fun fromEpochMillis(millis: Long?): Instant? = millis?.let { Instant.ofEpochMilli(it) }
}
