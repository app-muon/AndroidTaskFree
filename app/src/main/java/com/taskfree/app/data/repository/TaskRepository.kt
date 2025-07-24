// TaskRepository.kt
package com.taskfree.app.data.repository

import android.util.Log
import androidx.room.withTransaction
import com.taskfree.app.data.database.AppDatabase
import com.taskfree.app.data.entities.Category
import com.taskfree.app.data.entities.Task
import com.taskfree.app.data.entities.TaskWithCategoryInfo
import com.taskfree.app.domain.model.Recurrence
import com.taskfree.app.domain.model.TaskInput
import com.taskfree.app.domain.model.TaskStatus
import com.taskfree.app.domain.model.calculateNextValidDueDate
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

class TaskRepository(private val database: AppDatabase) {
    suspend fun snapshot(): List<Task> = database.taskDao().getAllNow()

    suspend fun replaceAll(cats: List<Category>, tasks: List<Task>) {
        Log.d("Backup", "replaceAll cats=${cats.size} tasks=${tasks.size}")
        database.withTransaction {
            val d1 = database.categoryDao().deleteAll()
            val d2 = database.taskDao().deleteAll()
            val i1 = database.categoryDao().insertAll(cats).size
            val i2 = database.taskDao().insertAll(tasks).size
            Log.d("Backup", "delC=$d1 delT=$d2  insC=$i1 insT=$i2")
        }
    }

    suspend fun saveTask(task: Task): Int {
        val updated = database.taskDao().update(task)
        require(updated > 0) { "Update failed for task id=${task.id}" }
        return updated
    }

    private suspend fun maxPositionInCategory(categoryId: Int): Int {
        return database.taskDao().getMaxPos(categoryId) ?: -1
    }

    suspend fun reindexAllTaskPageOrders() {
        val categories = database.taskDao().getAllCategoryIds()
        for (catId in categories) {
            val orderedTasks = database.taskDao().tasksByCategory(catId)
            val reindexed = orderedTasks.mapIndexed { index, task ->
                task.copy(singleCategoryPageOrder = index)
            }
            database.taskDao().updateMany(reindexed)
        }
    }

    suspend fun createTask(input: TaskInput): Int {
        require(input.title.isNotBlank()) { "Task title cannot be blank" }
        val trimmedTitle = sanitizeTaskTitle(input.title)
        val baseDate = if (input.recurrence != Recurrence.NONE) {
            requireNotNull(input.dueDate) { "Recurring tasks must have a dueDate" }
            input.dueDate
        } else null

        val task = Task(
            categoryId = input.categoryId,
            text = trimmedTitle,
            due = input.dueDate,
            baseDate = baseDate,
            singleCategoryPageOrder = maxPositionInCategory(input.categoryId) + 1,
            allCategoryPageOrder = database.taskDao().maxTodoOrder()?.plus(1) ?: 0,
            completedDate = null,
            recurrence = input.recurrence,
            status = TaskStatus.TODO,
            isArchived = false,
            reminderTime = input.reminderTime
        )
        Log.d("TaskRepository", "Creating task: $task")
        return database.taskDao().insert(task).toInt()
    }

    suspend fun updateTaskOrder(tasks: List<Task>) {
        Log.d("TaskRepository", "Moving task: $tasks")
        database.withTransaction {
            tasks.forEach { task ->
                val rows = database.taskDao().update(task)
                Log.d(
                    "Repo-reorder",
                    "id=${task.id} rows=$rows newAll=${task.allCategoryPageOrder}"
                )
                require(rows > 0) { "Task update failed for id=${task.id}" }
            }
        }
    }

    suspend fun archiveTask(task: Task) {
        Log.d("TaskRepository", "Archiving task: $task")
        val archived = task.copy(isArchived = true)
        val updatedRows = database.taskDao().update(archived)
        require(updatedRows > 0) { "Archive failed: no row updated for id=${task.id}" }
    }

    suspend fun archiveSingleOccurrence(task: Task) {
        if (task.recurrence == Recurrence.NONE) {
            archiveTask(task)
            return
        }

        database.withTransaction {
            try {
                // First spawn the next occurrence
                val baseDate = task.baseDate
                    ?: throw IllegalArgumentException("null baseDate but recurrence is not NONE")

                val nextDueDate = task.recurrence.calculateNextValidDueDate(baseDate)

                if (nextDueDate != null) {
                    createTask(
                        TaskInput(task.text, nextDueDate, task.recurrence, task.categoryId)
                    )
                    Log.d("TaskRepository", "Spawned next recurring task due: $nextDueDate")
                }

                // Then mark current task as archived/deleted (soft delete)
                val updatedTask = task.copy(
                    isArchived = true, status = TaskStatus.DONE, // Or create a DELETED status
                    completedDate = LocalDate.now()
                )

                val updatedRows = database.taskDao().update(updatedTask)
                if (updatedRows == 0) {
                    throw IllegalStateException("Failed to archive task with id: ${task.id}")
                }

            } catch (e: Exception) {
                Log.e(
                    "TaskRepository", "Failed to delete single occurrence of task: ${task.id}", e
                )
                throw e
            }
        }
    }

    fun observeTasksDueBy(date: LocalDate?, archived: Boolean): Flow<List<TaskWithCategoryInfo>> {
        return database.taskDao().taskListForDate(date, archived)
    }

    suspend fun updateTaskStatus(task: Task, newStatus: TaskStatus) {
        database.withTransaction {
            val wasDone = task.status == TaskStatus.DONE
            val nowDone = newStatus == TaskStatus.DONE

            // Update core fields
            val updated = task.copy(
                status = newStatus, completedDate = if (nowDone) LocalDate.now() else null
            )
            val rowsUpdated = database.taskDao().update(updated)
            if (rowsUpdated == 0) {
                Log.w("TaskRepository", "Task update failed — no matching task with id=${task.id}")
            }

            if (task.recurrence != Recurrence.NONE) {
                val baseDate = task.baseDate
                    ?: throw IllegalArgumentException("null baseDate but recurrence is not NONE")

                if (nowDone && !wasDone) {
                    // Only create next task if newly marked as DONE
                    val nextDueDate = task.recurrence.calculateNextValidDueDate(baseDate)
                    if (nextDueDate != null) {
                        createTask(
                            TaskInput(task.text, nextDueDate, task.recurrence, task.categoryId)
                        )
                    }
                }

                if (!nowDone && wasDone) {
                    // Remove next instance only if unmarking DONE
                    val nextDueDate = task.recurrence.calculateNextValidDueDate(baseDate)
                    if (nextDueDate != null) {
                        database.taskDao().deleteNextInstance(
                            categoryId = task.categoryId,
                            text = task.text,
                            rec = task.recurrence,
                            dueNext = nextDueDate
                        )
                    }
                }
            }
        }
    }

    suspend fun updateTaskDetails(
        task: Task,
        newTitle: String,
        newDueDate: LocalDate?,
        newRecurrence: Recurrence,
        newCategoryId: Int,
        newReminderTime: Instant?
    ) {
        val baseDate = if (newRecurrence != Recurrence.NONE) newDueDate else null
        val updated = task.copy(
            text = sanitizeTaskTitle(newTitle),
            due = newDueDate,
            baseDate = baseDate,
            categoryId = newCategoryId,
            recurrence = newRecurrence,
            reminderTime = newReminderTime
        )
        Log.d("TaskRepository", "Updating task: original $task")
        Log.d("TaskRepository", "Updating task: update $updated")
        database.taskDao().update(updated)
    }

    suspend fun archiveTasksCompletedBeforeToday() {
        val today = LocalDate.now()
        Log.d("TaskRepository", "deleting tasks completed before: $today")
        database.taskDao().archiveOldCompletedTasks(today)
    }

    suspend fun archiveCompletedInCategory(catId: Int) {
        database.taskDao().archiveCompletedInCategory(catId)
    }

    suspend fun deleteAllArchivedTasks() {
        Log.d("TaskRepository", "Permanently deleting deleted tasks")
        database.taskDao().permanentlyDeleteArchivedTasks()
    }

    private fun sanitizeTaskTitle(title: String): String {
        return title.trim().replace(Regex("[\\r\\n]+"), " ") // Replace line breaks with space
            .take(MAX_TASK_TITLE_LENGTH)
    }

    companion object {
        private const val MAX_TASK_TITLE_LENGTH = 100
    }

}
