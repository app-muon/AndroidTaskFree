@file:kotlinx.serialization.UseSerializers(com.taskfree.app.data.serialization.InstantSerializer::class)

package com.taskfree.app.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.time.Instant

const val TASK_NOTE_LIMIT = 2_000
const val TASK_TITLE_LIMIT = 100

@Serializable
@Entity(
    foreignKeys = [ForeignKey(
        entity = Task::class,
        parentColumns = ["id"],
        childColumns = ["taskId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("taskId")]
)
data class TaskNote(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val taskId: Int,
    val text: String,
    val createdAt: Instant,
    val editedAt: Instant? = null
)
