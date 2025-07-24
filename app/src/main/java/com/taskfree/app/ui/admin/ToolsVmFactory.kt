// ui/admin/ToolsVmFactory.kt
package com.taskfree.app.ui.admin

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.taskfree.app.data.repository.CategoryRepository
import com.taskfree.app.data.repository.TaskRepository
import com.taskfree.app.util.db

class ToolsVmFactory(
    private val app: Application
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val taskRepo = TaskRepository(app.db)
        val catRepo = CategoryRepository(app.db)
        @Suppress("UNCHECKED_CAST")
        return ToolsViewModel(taskRepo, catRepo) as T
    }
}
