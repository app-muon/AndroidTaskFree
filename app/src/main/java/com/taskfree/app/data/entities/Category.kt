// Category.kt
package com.taskfree.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@Entity
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val color: Long,            /* ARGB hex, e.g. 0xFF3F51B5 */
    val categoryPageOrder: Int = 0,

    @ColumnInfo(defaultValue = "0")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val isDeleted: Boolean = false
)
