// ui/task/components/TaskSearchAndFilter.kt
package com.taskfree.app.ui.task.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taskfree.app.R
import com.taskfree.app.domain.model.TaskStatus
import com.taskfree.app.ui.components.DragHandle
import com.taskfree.app.ui.components.InfoPill
import com.taskfree.app.ui.components.SortMode
import com.taskfree.app.ui.mapper.backgroundColor
import com.taskfree.app.ui.mapper.displayName

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskSearchAndFilter(
    state: TaskListState,
    visibleStatuses: List<TaskStatus>,
    onUpdateState: (TaskListState) -> Unit,
    onToggleStatusVisibility: (TaskStatus) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colorResource(R.color.top_bar_colour))
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colorResource(R.color.top_bar_colour)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clickable {
                        onUpdateState(
                            state.copy(
                                searchVisible = !state.searchVisible,
                                searchText = if (state.searchVisible) "" else state.searchText
                            )
                        )
                    }
                    .padding(8.dp)
            ) {
                Icon(
                    imageVector = if (state.searchVisible) Icons.Default.Close else Icons.Default.Search,
                    contentDescription = null,
                    tint = colorResource(R.color.surface_colour),
                    modifier = Modifier.padding(end = 4.dp)
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .weight(1f)
            ) {
                TaskStatus.entries.forEach { status ->
                    val selected = status in visibleStatuses
                    InfoPill(
                        title = status.displayName(),
                        selectedFillColor = status.backgroundColor(),
                        big = true,
                        border = !selected,
                        selected = selected,
                        modifier = Modifier
                            .padding(horizontal = 1.dp)
                            .clickable { onToggleStatusVisibility(status) })
                }
            }

            DragHandle(
                icon = state.sortMode.icon,
                modifier = Modifier
                    .padding(end = 12.dp)
                    .clickable {
                        val next = state.sortMode.next()
                        onUpdateState(state.copy(sortMode = next))

                        val msg = when (next) {
                            SortMode.DATE_ASC -> context.getString(R.string.sort_order_date_asc)
                            SortMode.DATE_DESC -> context.getString(R.string.sort_order_date_desc)
                            SortMode.USER -> context.getString(R.string.sort_order_custom)
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
            )
        }

        if (state.searchVisible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = state.searchText,
                    onValueChange = { onUpdateState(state.copy(searchText = it)) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = colorResource(R.color.surface_colour), fontSize = 14.sp
                    ),
                    cursorBrush = SolidColor(colorResource(R.color.surface_colour)),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .border(
                                    1.dp,
                                    colorResource(R.color.surface_colour).copy(alpha = 0.6f),
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            if (state.searchText.isEmpty()) {
                                Text(
                                    stringResource(R.string.search_tasks_placeholder),
                                    color = colorResource(R.color.surface_colour).copy(alpha = 0.6f),
                                    fontSize = 14.sp
                                )
                            }
                            innerTextField()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}