// AppDatabase.kt
package com.taskfree.app.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.taskfree.app.data.converters.InstantConverter
import com.taskfree.app.data.converters.LocalDateConverter
import com.taskfree.app.data.converters.RecurrenceConverter
import com.taskfree.app.data.converters.TaskStatusConverter
import com.taskfree.app.data.entities.Category
import com.taskfree.app.data.entities.Task

@Database(entities = [Category::class, Task::class], version = 16, exportSchema = false)
@TypeConverters(TaskStatusConverter::class, LocalDateConverter::class, InstantConverter::class,
    RecurrenceConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun taskDao(): TaskDao
}