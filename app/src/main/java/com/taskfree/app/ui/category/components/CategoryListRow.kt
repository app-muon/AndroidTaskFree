// CategoryListRow.kt
package com.taskfree.app.ui.category.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.taskfree.app.R
import com.taskfree.app.data.entities.Category
import com.taskfree.app.ui.components.DragHandle
import sh.calvin.reorderable.ReorderableCollectionItemScope

@Stable
data class CategoryRowData(
    val id: Int, val title: AnnotatedString, val color: Color
)

@Composable
private fun rememberCategoryRowData(category: Category, count: Int): CategoryRowData {
    return remember(category.id, category.title, category.color, count) {
        CategoryRowData(
            id = category.id, title = buildAnnotatedString {
                append(category.title)
                if (count > 0) {
                    append("  ")
                    withStyle(SpanStyle(color = Color.Gray)) {
                        append(count.toString())
                    }
                }
            }, color = Color(category.color)
        )
    }
}

@Composable
fun ReorderableCollectionItemScope.CategoryListRow(
    category: Category, count: Int, isDragging: Boolean, onClick: () -> Unit
) {
    val rowData = rememberCategoryRowData(category, count)

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

            DragHandle(
                modifier = Modifier.draggableHandle()
            )
        }
    }
}