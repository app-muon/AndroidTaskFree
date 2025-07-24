// ui/category/CategoryVmFactory.kt
package com.taskfree.app.ui.category

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.taskfree.app.data.repository.CategoryRepository
import com.taskfree.app.data.repository.TaskRepository
import com.taskfree.app.util.db          // your DB-extension

class CategoryVmFactory(
    private val app: Application
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CategoryViewModel::class.java)) {
            val db = app.db
            val repo = CategoryRepository(db)
            val taskRepo = TaskRepository(db)
            return CategoryViewModel(repo, taskRepo) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
