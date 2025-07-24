// Category.kt
package com.taskfree.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val color: Long,            /* ARGB hex, e.g. 0xFF3F51B5 */
    val categoryPageOrder: Int = 0
)
