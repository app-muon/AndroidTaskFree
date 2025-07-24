// TaskDao.kt
package com.taskfree.app.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.taskfree.app.data.entities.IncompleteTaskCount
import com.taskfree.app.data.entities.Task
import com.taskfree.app.data.entities.TaskWithCategoryInfo
import com.taskfree.app.domain.model.Recurrence
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate


@Dao
interface TaskDao {

    // The items returned by the TaskList query are:
    // (1) Any task with no filtering on due or completed date, if the input date is Null.
    // (2) Tasks with a due date on or before now, which haven't been completed yet.
    // (3) Tasks with a due date on or before now, which have been completed now or after. (These are DONE)
    // (4) Anything which was set to completed (i.e. DONE) today.
    @Transaction
    @Query(
        """
    SELECT Task.*
    FROM Task
    JOIN Category ON Category.id = Task.categoryId
    WHERE (
        (:date IS NULL) OR 
        (due IS NOT NULL AND due <= :date AND (completedDate IS NULL OR completedDate >= :date))
        OR 
        (completedDate == :date))
    AND isArchived = :archived
    ORDER BY allCategoryPageOrder ASC
    """
    )
    fun taskListForDate(date: LocalDate?, archived: Boolean): Flow<List<TaskWithCategoryInfo>>

    @Query("SELECT MAX(allCategoryPageOrder) FROM Task")
    suspend fun maxTodoOrder(): Int?

//    @Query("DELETE FROM Task WHERE categoryId = :id AND completedDate IS NOT NULL")
//    suspend fun deleteCompleted(id: Int)

    @Query("SELECT MAX(singleCategoryPageOrder) FROM Task WHERE categoryId = :categoryId")
    suspend fun getMaxPos(categoryId: Int): Int?

    @Query(
        """
    SELECT categoryId, COUNT(*) AS count
    FROM Task
    WHERE status != 'DONE'
    GROUP BY categoryId
"""
    )
    fun countNotDoneByCategory(): Flow<List<IncompleteTaskCount>>

    @Query("SELECT DISTINCT categoryId FROM Task")
    suspend fun getAllCategoryIds(): List<Int>

    @Query("DELETE FROM Task WHERE categoryId = :categoryId")
    suspend fun deleteTasksInCategory(categoryId: Int)

    @Update
    suspend fun updateMany(tasks: List<Task>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: Task): Long

    @Update
    suspend fun update(task: Task): Int

    @Delete
    suspend fun delete(task: Task)

    // ❺ Remove completed recurring “next instance”
    @Query(
        """
DELETE FROM Task
WHERE categoryId = :categoryId
  AND text   = :text
  AND recurrence = :rec
  AND due    = :dueNext
  AND completedDate IS NULL
"""
    )
    suspend fun deleteNextInstance(
        categoryId: Int, text: String, rec: Recurrence, dueNext: LocalDate
    )

    @Query("SELECT * FROM Task WHERE categoryId = :catId ORDER BY singleCategoryPageOrder")
    suspend fun tasksByCategory(catId: Int): List<Task>

    @Query(
        """
        UPDATE Task
        SET isArchived = 1
        WHERE completedDate < :today
          AND completedDate IS NOT NULL
          AND isArchived = 0
    """
    )
    suspend fun archiveOldCompletedTasks(today: LocalDate)

    /** Archive every completed (status = DONE) task inside one category */
    @Query(
        """
        UPDATE Task
        SET isArchived = 1
        WHERE categoryId = :catId
          AND completedDate IS NOT NULL     -- completed
          AND isArchived = 0                -- but not yet archived
    """
    )
    suspend fun archiveCompletedInCategory(catId: Int)

    @Transaction
    @Query("DELETE FROM Task WHERE isArchived = 1")
    suspend fun permanentlyDeleteArchivedTasks()

    @Query("SELECT id, reminderTime FROM Task WHERE reminderTime > :from")
    suspend fun upcomingReminders(from: Instant): List<IdTimeTuple>

    data class IdTimeTuple(val id: Int, val reminderTime: Instant)

    @Transaction
    @Query("SELECT Task.id, Task.text, Task.due, Task.recurrence, Category.title AS catTitle, Category.color AS catColor FROM Task JOIN Category ON Category.id = Task.categoryId WHERE Task.id = :id")
    suspend fun taskWithCatById(id: Int): TaskRow?

    data class TaskRow(
        val id: Int,
        val text: String,
        val due: LocalDate?,
        val recurrence: Recurrence,
        val catTitle: String,
        val catColor: Long
    )

    @Query("SELECT * FROM Task")
    suspend fun getAllNow(): List<Task>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tasks: List<Task>): List<Long>

    @Query("DELETE FROM Task") suspend fun deleteAll(): Int
}
