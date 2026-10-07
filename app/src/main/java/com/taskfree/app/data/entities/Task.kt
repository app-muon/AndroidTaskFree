//Task.kt
@file:UseSerializers(
    LocalDateSerializer::class,
    InstantSerializer::class
)
package com.taskfree.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.taskfree.app.data.serialization.InstantSerializer
import com.taskfree.app.data.serialization.LocalDateSerializer
import com.taskfree.app.domain.model.Recurrence
import com.taskfree.app.domain.model.TaskStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import java.time.LocalDate

// ❷ Give Task a new column (default = NONE)
@Serializable
@Entity(
    foreignKeys = [ForeignKey(
        entity = Category::class,
        parentColumns = ["id"],
        childColumns = ["categoryId"],
        onDelete = ForeignKey.CASCADE
    ), ForeignKey(
        entity = Task::class,
        parentColumns = ["id"],
        childColumns = ["sourceTaskId"],
        onDelete = ForeignKey.SET_NULL,
        deferred = true
    )],
    indices = [Index(value = ["categoryId"]), Index(value = ["sourceTaskId"], unique = true)]
)
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val categoryId: Int,
    val text: String,
    val due: LocalDate? = null,
    val baseDate: LocalDate? = null,
    val singleCategoryPageOrder: Int,
    val allCategoryPageOrder: Int = 0,
    val completedDate: LocalDate? = null,

    @ColumnInfo(defaultValue = "NONE")
    val recurrence: Recurrence = Recurrence.NONE,

    @ColumnInfo(defaultValue = "TODO")
    val status: TaskStatus = TaskStatus.TODO,

    @ColumnInfo(defaultValue = "0")
    val isArchived: Boolean = false,

    val reminderTime: Instant? = null,

    /** When the user first added this task; inherited by automatic recurrences. */
    val originalCreatedAt: Instant? = null,

    /** When this occurrence was generated. Null for tasks predating date tracking. */
    val occurrenceCreatedAt: Instant? = null,

    /** The occurrence that generated this task. Manual additions and legacy rows have no link. */
    val sourceTaskId: Int? = null
)
