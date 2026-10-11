// CategoryListRow.kt
package com.taskfree.app.ui.category.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.taskfree.app.R
import com.taskfree.app.data.entities.Category
import com.taskfree.app.domain.model.TaskStatus
import com.taskfree.app.ui.components.DragHandle
import com.taskfree.app.ui.components.InfoPill
import com.taskfree.app.ui.mapper.backgroundColor
import com.taskfree.app.ui.mapper.displayName
import sh.calvin.reorderable.ReorderableCollectionItemScope

@Stable
data class CategoryRowData(
    val id: Int, val title: String, val color: Color
)

@Composable
private fun rememberCategoryRowData(category: Category): CategoryRowData {
    return remember(category.id, category.title, category.color) {
        CategoryRowData(id = category.id, title = category.title, color = Color(category.color))
    }
}

@Composable
fun ReorderableCollectionItemScope.CategoryListRow(
    category: Category, counts: Map<TaskStatus, Int>, isDragging: Boolean, onClick: () -> Unit
) {
    val rowData = rememberCategoryRowData(category)

    // Subtle visual feedback for dragging state
    val backgroundColor = if (isDragging) {
        colorResource(R.color.category_row_colour).copy(alpha = 0.95f)
    } else {
        colorResource(R.color.category_row_colour)
    }

    val elevation = if (isDragging) 2.dp else 0.dp

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .combinedClickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(elevation),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(
                        color = rowData.color, shape = RoundedCornerShape(2.dp)
                    )
            )

            Spacer(Modifier.width(12.dp))

            Text(
                text = rowData.title,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = colorResource(R.color.surface_colour)
            )

            StatusCountPills(counts)

            Spacer(Modifier.width(8.dp))

            DragHandle(
                modifier = Modifier.draggableHandle()
            )
        }
    }
}

/** One pill per status, always in the same order so they line up down the list; zeros are dimmed. */
@Composable
private fun StatusCountPills(counts: Map<TaskStatus, Int>) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        TaskStatus.entries.forEach { status ->
            val count = counts[status] ?: 0
            val description = "$count ${status.displayName()}"
            InfoPill(
                title = count.toString(),
                selectedFillColor = status.backgroundColor(),
                selected = count > 0,
                border = count == 0,
                modifier = Modifier
                    .widthIn(min = 22.dp)
                    .semantics { contentDescription = description }
            )
        }
    }
}
