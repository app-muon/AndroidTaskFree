// testutil/TestDb.kt
package com.taskfree.app.testutil

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.taskfree.app.data.database.AppDatabase
import com.taskfree.app.data.entities.Category
import com.taskfree.app.data.entities.Task
import com.taskfree.app.domain.model.Recurrence
import com.taskfree.app.domain.model.TaskStatus
import java.time.Instant
import java.time.LocalDate

/** Fresh in-memory database; requires a Robolectric (AndroidJUnit4) test. */
fun inMemoryDb(): AppDatabase =
    Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        AppDatabase::class.java
    ).allowMainThreadQueries().build()

suspend fun AppDatabase.insertCategory(
    title: String = "Category",
    order: Int = 0,
    isDeleted: Boolean = false,
    color: Long = 0xFF3F51B5
): Int = categoryDao().insertAll(
    listOf(Category(title = title, color = color, categoryPageOrder = order, isDeleted = isDeleted))
).single().toInt()

suspend fun AppDatabase.insertTask(task: Task): Int = taskDao().insert(task).toInt()

fun task(
    categoryId: Int,
    text: String = "Task",
    due: LocalDate? = null,
    recurrence: Recurrence = Recurrence.NONE,
    baseDate: LocalDate? = if (recurrence != Recurrence.NONE) due else null,
    status: TaskStatus = TaskStatus.TODO,
    completedDate: LocalDate? = null,
    isArchived: Boolean = false,
    reminderTime: Instant? = null,
    singleOrder: Int = 0,
    allOrder: Int = 0,
    id: Int = 0
) = Task(
    id = id,
    categoryId = categoryId,
    text = text,
    due = due,
    baseDate = baseDate,
    singleCategoryPageOrder = singleOrder,
    allCategoryPageOrder = allOrder,
    completedDate = completedDate,
    recurrence = recurrence,
    status = status,
    isArchived = isArchived,
    reminderTime = reminderTime
)
