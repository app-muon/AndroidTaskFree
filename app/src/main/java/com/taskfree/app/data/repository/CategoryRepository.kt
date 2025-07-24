// CategoryRepository.kt
package com.taskfree.app.data.repository

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.room.withTransaction
import com.taskfree.app.data.database.AppDatabase
import com.taskfree.app.data.entities.Category
import com.taskfree.app.ui.theme.FallBackColourForCategory
import com.taskfree.app.ui.theme.categoryPalette
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map


class CategoryRepository(private val database: AppDatabase) {
    suspend fun snapshot(): List<Category> = database.categoryDao().getAllNow()

    fun observeAllCategories(): Flow<List<Category>> {
        return database.categoryDao().all()
    }

    private fun Color.asLongArgb(): Long =
        toArgb().toLong() and 0xFFFFFFFFL      // keep 32 bits, prevent sign-extension

    suspend fun createCategory(title: String) {
        val assignedColor = categoryPalette.randomOrNull() ?: FallBackColourForCategory
        val safeColorLong = assignedColor.asLongArgb()
        val lastCategoryOrder = database.categoryDao().getMaxCategoryOrder() ?: -1
        val newCategory = Category(
            title = sanitizeCategoryTitle(title),
            color = safeColorLong,
            categoryPageOrder = lastCategoryOrder + 1
        )

        database.categoryDao().insert(newCategory)
    }

    suspend fun deleteCategoryWithTasks(category: Category) {
        database.withTransaction {
            database.taskDao().deleteTasksInCategory(category.id)
            val rows = database.categoryDao().delete(category)
            require(rows > 0) { "Delete failed for category id=${category.id}" }
        }
    }

    suspend fun updateCategoryTitle(category: Category, newTitle: String) {
        val updated = category.copy(title = sanitizeCategoryTitle(newTitle))
        val rows = database.categoryDao().update(updated)
        require(rows > 0) { "Update failed for category id=${category.id}" }
    }

    fun observeIncompleteTaskCounts(): Flow<Map<Int, Int>> =
        database.taskDao().countNotDoneByCategory()
            .map { it.associate { row -> row.categoryId to row.count } }


    suspend fun updateCategoryOrder(resequenced: List<Category>) {
        val rows = database.categoryDao().updateAll(resequenced)
        require(rows == resequenced.size) {
            "Re-ordering failed: updated $rows of ${resequenced.size} rows"
        }
    }

    private fun sanitizeCategoryTitle(title: String): String {
        require(title.isNotBlank()) { "New title cannot be blank" }
        return title.trim().take(MAX_CATEGORY_TITLE_LENGTH)
    }

    companion object {
        private const val MAX_CATEGORY_TITLE_LENGTH = 35
    }

}
