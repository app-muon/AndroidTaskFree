// ui/category/CategoryViewModel.kt
package com.taskfree.app.ui.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskfree.app.data.entities.Category
import com.taskfree.app.data.repository.CategoryRepository
import com.taskfree.app.data.repository.TaskRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CategoryViewModel(
    private val repo: CategoryRepository,
    private val taskRepo: TaskRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    // Track drag state separately from data state
    private val _isDragging = MutableStateFlow(false)
    private val _dragReorderedCategories = MutableStateFlow<List<Category>?>(null)

    /** single observable screen state */
    val uiState: StateFlow<CategoryUiState> = combine(
        repo.observeAllCategories(),
        repo.observeIncompleteTaskCounts(),
        _isDragging,
        _dragReorderedCategories
    ) { cats, counts, isDragging, dragReordered ->
        CategoryUiState(
            // Use drag-reordered list if currently dragging, otherwise use repo data
            categories = if (isDragging && dragReordered != null) dragReordered else cats,
            incompleteCounts = counts,
            isDragging = isDragging,
            isInitialLoadPending = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = CategoryUiState(isInitialLoadPending = true)
    )

    /* ─── Commands ───────────────────────────────────────────── */

    fun add(title: String) = launch { repo.createCategory(title) }
    fun delete(category: Category) = launch { repo.deleteCategoryWithTasks(category) }
    fun rename(category: Category, newTitle: String) =
        launch { repo.updateCategoryTitle(category, newTitle) }

    /**
     * Called when drag starts - initializes drag state
     */
    fun onDragStart() {
        _isDragging.value = true
        // Initialize drag reordered list with current repo data
        val currentCategories = uiState.value.categories
        _dragReorderedCategories.value = currentCategories
    }

    /**
     * Called during drag - only updates visual state, no data persistence
     */
    fun onDragMove(from: Int, to: Int) {
        // Only process if we're in drag mode
        if (!_isDragging.value) return

        val currentDragList = _dragReorderedCategories.value ?: return

        // Validate indices
        if (from !in currentDragList.indices || to !in currentDragList.indices || from == to) {
            return
        }

        // Create reordered list for visual feedback only
        val mutableList = currentDragList.toMutableList()
        val movedCategory = mutableList.removeAt(from)
        mutableList.add(to, movedCategory)

        // Update the visual state immediately (no database call)
        _dragReorderedCategories.value = mutableList
    }

    /**
     * Called when drag ends - persists the final order to database
     */
    fun onDragEnd() {
        val finalOrder = _dragReorderedCategories.value

        // Reset drag state immediately for responsive UI
        _isDragging.value = false
        _dragReorderedCategories.value = null

        // Persist to database if we have a reordered list
        if (finalOrder != null) {
            viewModelScope.launch(ioDispatcher) {
                try {
                    // Update categoryPageOrder for all items based on final positions
                    val resequenced = finalOrder.mapIndexed { index, category ->
                        category.copy(categoryPageOrder = index)
                    }

                    // Single database update with final order
                    repo.updateCategoryOrder(resequenced)
                } catch (e: Exception) {
                    // Handle potential database errors
                    // The UI will automatically revert to repository state since drag ended
                }
            }
        }
    }

    fun archiveCompleted(category: Category) = launch {
        taskRepo.archiveCompletedInCategory(category.id)
    }

    /* helper */
    private fun launch(block: suspend () -> Unit) = viewModelScope.launch(ioDispatcher) { block() }

}
