// TaskStatusUIMapper.kt
package com.taskfree.app.ui.mapper

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import com.taskfree.app.R
import com.taskfree.app.domain.model.TaskStatus

@get:StringRes
val TaskStatus.labelRes: Int
    get() = when (this) {
        TaskStatus.TODO -> R.string.to_do_status
        TaskStatus.IN_PROGRESS -> R.string.doing_status
        TaskStatus.PENDING -> R.string.pending_status
        TaskStatus.DONE -> R.string.done_status

    }

@Composable
fun TaskStatus.displayName(): String = stringResource(labelRes)


@Composable
fun TaskStatus.backgroundColor(): Color = when (this) {
    TaskStatus.TODO -> colorResource(R.color.todo_colour)
    TaskStatus.IN_PROGRESS -> colorResource(R.color.in_progress_colour)
    TaskStatus.PENDING -> colorResource(R.color.pending_colour)
    TaskStatus.DONE -> colorResource(R.color.done_colour)
}


fun TaskStatus.icon(): ImageVector = when (this) {
    TaskStatus.TODO -> Icons.Default.Star
    TaskStatus.IN_PROGRESS -> Icons.Default.PlayArrow
    TaskStatus.PENDING -> Icons.Default.Refresh
    TaskStatus.DONE -> Icons.Default.Check
}
