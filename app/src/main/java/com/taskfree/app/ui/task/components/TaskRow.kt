package com.taskfree.app.ui.task.components

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.taskfree.app.R
import com.taskfree.app.data.entities.Category
import com.taskfree.app.data.entities.Task
import com.taskfree.app.domain.model.TaskStatus
import com.taskfree.app.ui.components.ArchivePill
import com.taskfree.app.ui.components.CategoryPill
import com.taskfree.app.ui.components.DragHandle
import com.taskfree.app.ui.components.DueChoice
import com.taskfree.app.ui.components.fromTask
import com.taskfree.app.ui.mapper.backgroundColor
import com.taskfree.app.ui.mapper.recurrenceLabel
import com.taskfree.app.ui.theme.RowTransparency
import isNotificationPassed
import sh.calvin.reorderable.ReorderableCollectionItemScope
import java.time.LocalDate

@Composable
fun ReorderableCollectionItemScope.TaskRow(
    task: Task,
    isDragging: Boolean = false,
    showHandle: Boolean = true,
    showCategory: Boolean = false,
    category: Category,
    onClick: () -> Unit = {}
) {
    val isOverdue = task.due?.let { it < LocalDate.now() && task.completedDate == null } == true
    val overdueColor = Color.Red.copy(alpha = 0.3f)
    val elevation = if (isDragging) 4.dp else 0.dp
    val backgroundColor = task.status.backgroundColor().copy(alpha = RowTransparency)

    val textColor =
        if (task.status == TaskStatus.DONE) Color.Gray else colorResource(R.color.surface_colour)
    val textDecoration = if (task.status == TaskStatus.DONE) TextDecoration.LineThrough else null


    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(elevation),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            if (isOverdue) {
                Box(
                    Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(
                            overdueColor, RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)
                        )
                        .align(Alignment.CenterStart)
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp), // keep normal padding
                verticalAlignment = Alignment.CenterVertically
            ) {

                Text(
                    text = task.text,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                    textDecoration = textDecoration
                )
                val isNotificationPassed = task.isNotificationPassed()

                if (task.reminderTime != null) {
                    Icon(
                        painter = painterResource(R.drawable.ic_notification),
                        contentDescription = stringResource(R.string.notification_heading),
                        tint = when {
                            task.status == TaskStatus.DONE -> Color.Gray
                            isNotificationPassed -> colorResource(R.color.surface_colour).copy(alpha = 0.2f)
                            else -> colorResource(R.color.surface_colour)
                        },
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(16.dp)
                    )
                }
                if (task.isArchived) {
                    ArchivePill(big = false)
                }
                if (showCategory) {
                    Spacer(modifier = Modifier.width(8.dp))
                    CategoryPill(category = category, big = false, selected = true)
                }
                val dueLabel = recurrenceLabel(DueChoice.fromTask(task), task.recurrence)
                if (dueLabel.isNotBlank()) {
                    Text(
                        dueLabel,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(
                            horizontal = 6.dp, vertical = 2.dp
                        ),
                        color = if (task.status == TaskStatus.DONE) Color.Gray else colorResource(R.color.surface_colour)
                    )
                }

                DragHandle(
                    show = showHandle, modifier = Modifier.draggableHandle()
                )
            }
        }
    }
}

