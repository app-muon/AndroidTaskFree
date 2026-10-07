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
    AND Task.isArchived = :archived
    AND (Task.isArchived = 1 OR Category.isDeleted = 0)
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
    WHERE status != 'DONE' AND isArchived = 0
    GROUP BY categoryId
"""
    )
    fun countNotDoneByCategory(): Flow<List<IncompleteTaskCount>>

    @Query("SELECT DISTINCT categoryId FROM Task")
    suspend fun getAllCategoryIds(): List<Int>

    @Query("UPDATE Task SET isArchived = 1 WHERE categoryId = :categoryId")
    suspend fun archiveTasksInCategory(categoryId: Int): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(task: Task): Long

    @Update
    suspend fun update(task: Task): Int

    @Query("""
        UPDATE Task
        SET singleCategoryPageOrder = :singleCategoryPageOrder,
            allCategoryPageOrder = :allCategoryPageOrder
        WHERE id = :id
    """)
    suspend fun updateOrder(id: Int, singleCategoryPageOrder: Int, allCategoryPageOrder: Int): Int

    @Delete
    suspend fun delete(task: Task)

    @Query(
        """
DELETE FROM Task
WHERE id = :id
  AND completedDate IS NULL
  AND status = 'TODO'
  AND isArchived = 0
"""
    )
    suspend fun deleteTodoOccurrence(id: Int): Int

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

    @Query("DELETE FROM Task WHERE isArchived = 1")
    suspend fun permanentlyDeleteArchivedTasks(): Int

    @Query(
        """
        SELECT Task.id AS id, Task.reminderTime AS reminderTime
        FROM Task
        JOIN Category ON Category.id = Task.categoryId
        WHERE Task.reminderTime > :from
          AND Task.isArchived = 0
          AND Category.isDeleted = 0
        """
    )
    suspend fun upcomingReminders(from: Instant): List<IdTimeTuple>

    data class IdTimeTuple(val id: Int, val reminderTime: Instant)

    @Query(
        """
        SELECT Task.id, Task.text, Task.due, Task.recurrence,
               Category.title AS catTitle, Category.color AS catColor
        FROM Task
        JOIN Category ON Category.id = Task.categoryId
        WHERE Task.id = :id
          AND Task.isArchived = 0
          AND Category.isDeleted = 0
        """
    )
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

    @Query("SELECT * FROM task WHERE id = :id LIMIT 1")
    suspend fun taskById(id: Int): Task?

    @Query("DELETE FROM Task WHERE id = :id")
    suspend fun deleteById(id: Int): Int

    @Query("""
SELECT id FROM task WHERE sourceTaskId = :sourceTaskId
""")
    suspend fun findNextInstanceId(sourceTaskId: Int): Int?
}
