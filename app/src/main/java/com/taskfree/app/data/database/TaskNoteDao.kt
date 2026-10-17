package com.taskfree.app.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.taskfree.app.data.entities.TaskNote
import kotlinx.coroutines.flow.Flow
import java.time.Instant

data class TaskNoteCount(val taskId: Int, val count: Int)

@Dao
interface TaskNoteDao {
    @Query("SELECT * FROM TaskNote WHERE taskId = :taskId ORDER BY createdAt DESC, id DESC")
    fun observe(taskId: Int): Flow<List<TaskNote>>

    @Query("SELECT taskId, COUNT(*) AS count FROM TaskNote GROUP BY taskId")
    fun observeCounts(): Flow<List<TaskNoteCount>>

    @Query("SELECT * FROM TaskNote ORDER BY id")
    suspend fun getAllNow(): List<TaskNote>

    @Query("SELECT * FROM TaskNote WHERE id = :id")
    suspend fun byId(id: Int): TaskNote?

    @Insert
    suspend fun insert(note: TaskNote): Long

    @Insert
    suspend fun insertAll(notes: List<TaskNote>)

    @Query("UPDATE TaskNote SET text = :text, editedAt = :editedAt WHERE id = :id")
    suspend fun updateText(id: Int, text: String, editedAt: Instant): Int

    @Query("DELETE FROM TaskNote WHERE id = :id")
    suspend fun delete(id: Int): Int
}
