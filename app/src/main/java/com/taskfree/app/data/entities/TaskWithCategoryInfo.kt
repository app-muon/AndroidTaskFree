// TaskWithCategoryInfo.kt
package com.taskfree.app.data.entities

import androidx.room.Embedded
import androidx.room.Relation

data class TaskWithCategoryInfo(
    @Embedded val task: Task,

    @Relation(
        parentColumn = "categoryId",
        entityColumn = "id"
    )
    val category: Category
)