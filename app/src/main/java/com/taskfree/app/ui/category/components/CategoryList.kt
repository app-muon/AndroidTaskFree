// CategoryList.kt
package com.taskfree.app.ui.category.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.taskfree.app.data.entities.Category
import com.taskfree.app.ui.components.thinVerticalScrollbar
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Stable
private class CategoryListCallbacks(
    val onDragStart: () -> Unit,
    val onDragMove: (Int, Int) -> Unit,
    val onDragEnd: () -> Unit,
    val onClick: (Category) -> Unit
)

@Composable
internal fun ColumnScope.CategoryList(
    categories: List<Category>,
    counts: Map<Int, Int>,
    onDragStart: () -> Unit,
    onDragMove: (Int, Int) -> Unit,
    onDragEnd: () -> Unit,
    onClick: (Category) -> Unit
) {
    val listState = rememberLazyListState()

    // Track drag state locally
    var isDragInProgress by remember { mutableStateOf(false) }
    var dragStartIndex by remember { mutableStateOf(-1) }

    // Wrap callbacks in a stable class to prevent recreation
    val callbacks = remember(onDragStart, onDragMove, onDragEnd, onClick) {
        CategoryListCallbacks(onDragStart, onDragMove, onDragEnd, onClick)
    }

    val reorderState = rememberReorderableLazyListState(
        lazyListState = listState,
        onMove = { from, to ->
            // Validate indices and only process meaningful moves
            if (from.index != to.index &&
                from.index in categories.indices &&
                to.index in categories.indices) {

                // Start drag if not already started
                if (!isDragInProgress) {
                    isDragInProgress = true
                    dragStartIndex = from.index
                    callbacks.onDragStart()
                }

                callbacks.onDragMove(from.index, to.index)
            }
        }
    )

    // Alternative approach: Track drag state through the ReorderableItem isDragging parameter
    // We'll handle drag start/end in the onMove callback and item composition

    LazyColumn(
        state = listState,
        modifier = Modifier
            .weight(1f)
            .thinVerticalScrollbar(
                listState = listState,
                thickness = 3.dp,
                color = Color.Gray
            )
    ) {
        items(
            items = categories,
            key = { category -> category.id }
        ) { category ->
            ReorderableItem(
                reorderState,
                key = category.id,
                modifier = Modifier.padding(vertical = 1.dp)
            ) { isDragging ->
                // Track drag end when isDragging becomes false
                LaunchedEffect(isDragging) {
                    if (!isDragging && isDragInProgress) {
                        isDragInProgress = false
                        callbacks.onDragEnd()
                        dragStartIndex = -1
                    }
                }

                CategoryListRow(
                    category = category,
                    count = counts[category.id] ?: 0,
                    isDragging = isDragging,
                    onClick = { callbacks.onClick(category) }
                )
            }
        }
    }
}