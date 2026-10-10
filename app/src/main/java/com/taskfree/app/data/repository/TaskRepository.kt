// TaskRepository.kt
package com.taskfree.app.data.repository

import android.util.Log
import androidx.room.withTransaction
import com.taskfree.app.BuildConfig
import com.taskfree.app.data.database.AppDatabase
import com.taskfree.app.data.database.TaskDao
import com.taskfree.app.data.entities.Category
import com.taskfree.app.data.entities.Task
import com.taskfree.app.data.entities.TaskWithCategoryInfo
import com.taskfree.app.domain.model.Recurrence
import com.taskfree.app.domain.model.TaskInput
import com.taskfree.app.domain.model.TaskStatus
import com.taskfree.app.domain.model.calculateNextValidDueDate
import com.taskfree.app.util.AppDateProvider
import com.taskfree.app.util.DateProvider
import com.taskfree.app.util.sameLocalTimeOn
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

data class UpdateResult(val nextCreatedId: Int? = null, val nextDeletedId: Int? = null)

/** Repeating occurrences completed before [asOf], grouped for the archive confirmation. */
data class CompletedRepeatsPreview(
    val asOf: LocalDate,
    val groups: List<TaskDao.CompletedRepeatCount>
) {
    val total: Int get() = groups.sumOf { it.count }
}

class TaskRepository(
    private val database: AppDatabase, private val dates: DateProvider = AppDateProvider.current
) {
    suspend fun snapshot(): List<Task> = database.taskDao().getAllNow()

    suspend fun taskById(id: Int): Task? = database.taskDao().taskById(id)

    suspend fun replaceAll(cats: List<Category>, tasks: List<Task>) {
        requireNoLiveTasksInDeletedCategories(cats, tasks)

        if (BuildConfig.DEBUG) {
            Log.d("Backup", "replaceAll cats=${cats.size} tasks=${tasks.size}")
        }
        database.withTransaction {
            val d2 = database.taskDao().deleteAll()
            val d1 = database.categoryDao().deleteAll()
            val i1 = database.categoryDao().insertAll(cats).size
            val i2 = database.taskDao().insertAll(tasks).size
            database.categoryDao().deleteDeletedWithoutTasks()
            if (BuildConfig.DEBUG) {
                Log.d("Backup", "delC=$d1 delT=$d2  insC=$i1 insT=$i2")
            }
        }
    }

    private suspend fun maxPositionInCategory(categoryId: Int): Int {
        return database.taskDao().getMaxPos(categoryId) ?: -1
    }

    suspend fun reindexAllTaskPageOrders() {
        database.withTransaction {
            val dao = database.taskDao()
            for (catId in dao.getAllCategoryIds()) {
                dao.tasksByCategory(catId).forEachIndexed { index, task ->
                    val rows = dao.updateOrder(task.id, index, task.allCategoryPageOrder)
                    require(rows > 0) { "Task update failed for id=${task.id}" }
                }
            }
        }
    }

    suspend fun createTask(input: TaskInput): Int = database.withTransaction {
        val createdAt = dates.nowInstant()
        insertLiveTask(input, originalCreatedAt = createdAt, occurrenceCreatedAt = createdAt)
    }

    private suspend fun insertLiveTask(
        input: TaskInput,
        // Required even when null: a legacy recurrence must keep its unknown original date.
        originalCreatedAt: Instant?,
        occurrenceCreatedAt: Instant = dates.nowInstant(),
        sourceTaskId: Int? = null
    ): Int {
        require(input.title.isNotBlank()) { "Task title cannot be blank" }
        val trimmedTitle = sanitizeTaskTitle(input.title)
        val baseDate = if (input.recurrence != Recurrence.NONE) {
            requireNotNull(input.dueDate) { "Recurring tasks must have a dueDate" }
            input.dueDate
        } else null

        requireActiveCategory(input.categoryId)
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
            reminderTime = input.reminderTime,
            originalCreatedAt = originalCreatedAt,
            occurrenceCreatedAt = occurrenceCreatedAt,
            sourceTaskId = sourceTaskId
        )
        if (BuildConfig.DEBUG) {
            Log.d("TaskRepository", "Creating task: $task")
        }
        return database.taskDao().insert(task).toInt()
    }

    suspend fun updateTaskOrder(tasks: List<Task>) {
        Log.d("TaskRepository", "Moving task: $tasks")
        database.withTransaction {
            tasks.forEach { task ->
                val rows = database.taskDao().updateOrder(
                    task.id, task.singleCategoryPageOrder, task.allCategoryPageOrder
                )
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "Repo-reorder",
                        "id=${task.id} rows=$rows newAll=${task.allCategoryPageOrder}"
                    )
                }
                require(rows > 0) { "Task update failed for id=${task.id}" }
            }
        }
    }

    suspend fun archiveTask(task: Task) = database.withTransaction {
        val current = requireNotNull(database.taskDao().taskById(task.id))
        database.taskDao().update(current.copy(isArchived = true))
    }

    /** Only explicit forward links define a series; legacy unlinked occurrences stay separate. */
    suspend fun archiveSeries(taskId: Int): List<Task> = database.withTransaction {
        val dao = database.taskDao()
        val visited = mutableSetOf<Int>()
        val archived = mutableListOf<Task>()
        var id: Int? = taskId
        while (id != null && visited.add(id)) {
            val current = dao.taskById(id) ?: break
            if (!current.isArchived) {
                val updated = current.copy(isArchived = true)
                dao.update(updated)
                archived.add(updated)
            }
            id = dao.findNextInstanceId(current.id)
        }
        archived
    }

    suspend fun unarchiveTask(taskId: Int) = database.withTransaction {
        val current = requireNotNull(database.taskDao().taskById(taskId))
        requireActiveCategory(current.categoryId)
        database.taskDao().update(current.copy(isArchived = false))
    }

    suspend fun archiveSingleOccurrence(task: Task): Int? = database.withTransaction {
        val current = requireNotNull(database.taskDao().taskById(task.id))
        if (current.isArchived) return@withTransaction null
        if (current.recurrence == Recurrence.NONE) {
            database.taskDao().update(current.copy(isArchived = true))
            return@withTransaction null
        }
        val nextId = if (current.status != TaskStatus.DONE) createNextOccurrence(current) else null
        database.taskDao().update(current.copy(
            isArchived = true,
            status = TaskStatus.DONE,
            completedDate = current.completedDate ?: dates.today()
        ))
        nextId
    }

    /** Called inside the same transaction as the completion/archive that creates the successor. */
    private suspend fun createNextOccurrence(task: Task): Int? {
        if (task.isArchived || task.recurrence == Recurrence.NONE ||
            !database.categoryDao().isActive(task.categoryId) ||
            database.taskDao().findNextInstanceId(task.id) != null
        ) return null

        val baseDate = requireNotNull(task.baseDate) { "Recurring task has no base date" }
        val nextDueDate = task.recurrence.calculateNextValidDueDate(baseDate, dates) ?: return null
        return insertLiveTask(
            TaskInput(
                title = task.text,
                dueDate = nextDueDate,
                recurrence = task.recurrence,
                categoryId = task.categoryId,
                reminderTime = task.reminderTime?.sameLocalTimeOn(nextDueDate)
            ),
            originalCreatedAt = task.originalCreatedAt,
            sourceTaskId = task.id
        )
    }

    fun observeTasksDueBy(date: LocalDate?, archived: Boolean): Flow<List<TaskWithCategoryInfo>> {
        return database.taskDao().taskListForDate(date, archived)
    }

    suspend fun updateTaskStatus(taskId: Int, newStatus: TaskStatus): UpdateResult {
        var createdId: Int? = null
        var deletedId: Int? = null

        database.withTransaction {
            val task = database.taskDao().taskById(taskId)
                ?: return@withTransaction

            val categoryActive = database.categoryDao().isActive(task.categoryId)
            if (!task.isArchived && !categoryActive) {
                return@withTransaction
            }

            val wasDone = task.status == TaskStatus.DONE
            val nowDone = newStatus == TaskStatus.DONE

            // update the current task row
            val updated = task.copy(
                status = newStatus,
                completedDate = if (nowDone) task.completedDate ?: dates.today() else null
            )
            database.taskDao().update(updated)

            if (nowDone && !wasDone) {
                createdId = createNextOccurrence(task)
            }
            if (!task.isArchived && categoryActive && !nowDone && wasDone) {
                val nextId = database.taskDao().findNextInstanceId(task.id)
                if (nextId != null && database.taskDao().deleteTodoOccurrence(nextId) > 0) {
                    deletedId = nextId
                }
            }
        }

        return UpdateResult(createdId, deletedId)
    }

    suspend fun updateTaskDetails(
        task: Task,
        newTitle: String,
        newDueDate: LocalDate?,
        newRecurrence: Recurrence,
        newCategoryId: Int,
        newReminderTime: Instant?
    ) {
        database.withTransaction {
            val current = requireNotNull(database.taskDao().taskById(task.id))
            if (!current.isArchived) {
                requireActiveCategory(newCategoryId)
            }
            database.taskDao().update(current.copy(
                text = sanitizeTaskTitle(newTitle),
                due = newDueDate,
                baseDate = if (newRecurrence != Recurrence.NONE) newDueDate else null,
                categoryId = newCategoryId,
                recurrence = newRecurrence,
                reminderTime = newReminderTime
            ))
            database.categoryDao().deleteDeletedWithoutTasks()
        }
    }

    suspend fun archiveTasksCompletedBeforeToday(): Int {
        val today = dates.today()
        if (BuildConfig.DEBUG) {
            Log.d("TaskRepository", "deleting tasks completed before: $today")
        }
        return database.taskDao().archiveOldCompletedTasks(today)
    }

    suspend fun archiveRecurringCompletedBeforeToday(): Int =
        archiveRecurringCompletedBefore(dates.today())

    /** Uses the preview's date, so a confirmation left open past midnight archives what it showed. */
    suspend fun archiveRecurringCompletedBefore(date: LocalDate): Int =
        database.taskDao().archiveOldCompletedRecurring(date)

    suspend fun previewRecurringCompletedBeforeToday(): CompletedRepeatsPreview {
        val today = dates.today()
        return CompletedRepeatsPreview(today, database.taskDao().oldCompletedRecurringCounts(today))
    }

    suspend fun archiveCompletedInCategory(catId: Int) {
        database.taskDao().archiveCompletedInCategory(catId)
    }

    suspend fun deleteAllArchivedTasks() {
        if (BuildConfig.DEBUG) {
            Log.d("TaskRepository", "Permanently deleting deleted tasks")
        }
        database.withTransaction {
            database.taskDao().permanentlyDeleteArchivedTasks()
            database.categoryDao().deleteDeletedWithoutTasks()
        }
    }

    suspend fun deleteTaskPermanently(task: Task) {
        database.withTransaction {
            database.taskDao().deleteById(task.id)
            database.categoryDao().deleteDeletedWithoutTasks()
        }
    }

    private fun sanitizeTaskTitle(title: String): String {
        return title.trim().replace(Regex("[\\r\\n]+"), " ") // Replace line breaks with space
            .take(MAX_TASK_TITLE_LENGTH)
    }

    private suspend fun requireActiveCategory(categoryId: Int) {
        require(database.categoryDao().isActive(categoryId)) {
            "Category $categoryId is deleted or missing"
        }
    }

    private fun requireNoLiveTasksInDeletedCategories(cats: List<Category>, tasks: List<Task>) {
        val deletedCategoryIds = cats.asSequence()
            .filter { it.isDeleted }
            .map { it.id }
            .toSet()
        val invalidTask = tasks.firstOrNull {
            !it.isArchived && it.categoryId in deletedCategoryIds
        }

        require(invalidTask == null) {
            "Unarchived task ${invalidTask!!.id} references deleted category ${invalidTask.categoryId}"
        }
    }

    companion object {
        private const val MAX_TASK_TITLE_LENGTH = 100
    }

}
