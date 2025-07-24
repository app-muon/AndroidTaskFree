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
    @Query("SELECT * FROM Category ORDER BY categoryPageOrder ASC")
    fun all(): Flow<List<Category>>

    @Query("SELECT MAX(categoryPageOrder) FROM Category")
    suspend fun getMaxCategoryOrder(): Int?

    @Query("SELECT * FROM Category WHERE id = :id LIMIT 1")
    fun get(id: Int): Flow<Category?>

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