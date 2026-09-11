// CategoryDao.kt
package com.taskfree.app.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.taskfree.app.data.entities.Category
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM Category WHERE isDeleted = 0 ORDER BY categoryPageOrder ASC")
    fun all(): Flow<List<Category>>

    @Query("SELECT MAX(categoryPageOrder) FROM Category WHERE isDeleted = 0")
    suspend fun getMaxCategoryOrder(): Int?

    @Query("SELECT * FROM Category WHERE id = :id AND isDeleted = 0 LIMIT 1")
    fun get(id: Int): Flow<Category?>

    @Query("SELECT EXISTS(SELECT 1 FROM Category WHERE id = :id AND isDeleted = 0)")
    suspend fun isActive(id: Int): Boolean

    @Query("UPDATE Category SET isDeleted = 1 WHERE id = :id")
    suspend fun markDeleted(id: Int): Int

    @Query(
        """
        DELETE FROM Category
        WHERE isDeleted = 1
          AND id NOT IN (SELECT DISTINCT categoryId FROM Task)
        """
    )
    suspend fun deleteDeletedWithoutTasks(): Int

    @Insert
    suspend fun insert(category: Category)

    @Delete
    suspend fun delete(category: Category): Int

    @Update
    suspend fun update(category: Category): Int

    @Update
    suspend fun updateAll(categories: List<Category>): Int

    @Query("DELETE FROM Category") suspend fun deleteAll(): Int

    @Query("SELECT * FROM Category")
    suspend fun getAllNow(): List<Category>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(categories: List<Category>): List<Long>
}
