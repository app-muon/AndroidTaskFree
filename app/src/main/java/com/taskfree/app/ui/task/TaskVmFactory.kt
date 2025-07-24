// TaskVmFactory.kt
package com.taskfree.app.ui.task

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.taskfree.app.data.repository.TaskRepository

class TaskVmFactory(
    private val appContext: Context, private val repo: TaskRepository
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")                         // single safe cast
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TaskViewModel::class.java)) {
            return TaskViewModel(appContext, repo) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
