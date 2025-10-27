package com.taskfree.app.ui.task.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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
import com.taskfree.app.util.AppDateProvider
import isNotificationPassed
import sh.calvin.reorderable.ReorderableCollectionItemScope
// If needed: import sh.calvin.reorderable.draggableHandle

@Composable
fun ReorderableCollectionItemScope.TaskRow(
    task: Task,
    isDragging: Boolean = false,
    showHandle: Boolean = true,
    showCategory: Boolean = false,
    category: Category,
    onClick: () -> Unit = {}
) {
    val dp = AppDateProvider.current
    val isOverdue = task.due?.let { it < dp.today() && task.completedDate == null } == true

    val elevation = if (isDragging) 4.dp else 0.dp
    val containerColor = task.status.backgroundColor().copy(alpha = RowTransparency)
    val surfaceColor = colorResource(R.color.surface_colour)

    val textColor = if (task.status == TaskStatus.DONE) Color.Gray else surfaceColor
    val textDecoration = if (task.status == TaskStatus.DONE) TextDecoration.LineThrough else null
    val dueLabel = recurrenceLabel(DueChoice.fromTask(task), task.recurrence)
    val isNotificationPassed = task.isNotificationPassed()
    val overdueStripeColor = Color.Red.copy(alpha = 0.3f)

    // Spacing
    val contentPaddingStart = 12.dp
    val contentPaddingEnd = 4.dp
    val contentPaddingTop = 6.dp
    val contentPaddingBottom = 6.dp
    val titleStartInner = 12.dp
    val handleGap = 4.dp
    val tierSpacing = 0.dp

    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val titleStyle: TextStyle = MaterialTheme.typography.bodyMedium

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(elevation),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(Modifier.fillMaxWidth()) {
            if (isOverdue) {
                Box(
                    Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(
                            overdueStripeColor,
                            RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)
                        )
                        .align(Alignment.CenterStart)
                )
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = contentPaddingStart,
                        top = contentPaddingTop,
                        end = contentPaddingEnd,
                        bottom = contentPaddingBottom
                    )
            ) {
                SubcomposeLayout { constraints ->
                    val titleStartPx = with(density) { titleStartInner.toPx().toInt() }
                    val handleGapPx = with(density) { handleGap.toPx().toInt() }
                    val tierSpacingPx = with(density) { tierSpacing.toPx().toInt() }
                    val maxW = constraints.maxWidth

                    // Measure a representative handle to reserve space ALWAYS
                    val handleMeasure = subcompose("HandleMeasure") {
                        DragHandle(show = true, modifier = Modifier.draggableHandle())
                    }.map { it.measure(Constraints()) }
                    val handleBaseWidth = handleMeasure.maxOfOrNull { it.width } ?: 0
                    val handleBaseHeight = handleMeasure.maxOfOrNull { it.height } ?: 0
                    val effectiveHandleWidth = handleBaseWidth + handleGapPx

                    // Compose the actual handle only if visible
                    val handlePlaceables = if (showHandle) {
                        subcompose("HandleReal") {
                            DragHandle(show = true, modifier = Modifier.draggableHandle())
                        }.map { it.measure(Constraints()) }
                    } else emptyList()
                    val handlePlaceWidth =
                        if (showHandle) (handlePlaceables.maxOfOrNull { it.width } ?: handleBaseWidth)
                        else handleBaseWidth
                    val handlePlaceHeight =
                        if (showHandle) (handlePlaceables.maxOfOrNull { it.height } ?: handleBaseHeight)
                        else handleBaseHeight

                    // Compact metadata (single line)
                    val metaCompactPlaceables = subcompose("MetaCompact") {
                        MetadataItems(
                            task = task,
                            showCategory = showCategory,
                            category = category,
                            dueLabel = dueLabel,
                            isNotificationPassed = isNotificationPassed,
                            textColor = textColor,
                            compact = true
                        )
                    }.map { it.measure(Constraints()) }
                    val metaCompactWidth = metaCompactPlaceables.maxOfOrNull { it.width } ?: 0
                    val metaCompactHeight = metaCompactPlaceables.maxOfOrNull { it.height } ?: 0

                    // Width available for 1-line title
                    val availableTitleWidth =
                        (maxW - titleStartPx - effectiveHandleWidth - metaCompactWidth)
                            .coerceAtLeast(0)

                    // Decide layout by measuring one-line title
                    val titleOneLine: TextLayoutResult = textMeasurer.measure(
                        text = task.text,
                        style = titleStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        constraints = Constraints(maxWidth = availableTitleWidth)
                    )
                    val wouldWrap = titleOneLine.hasVisualOverflow

                    if (!wouldWrap) {
                        // ---- Case A: single row ----
                        val topRowHeight = maxOf(
                            titleOneLine.size.height,
                            metaCompactHeight,
                            handlePlaceHeight
                        )

                        layout(width = maxW, height = topRowHeight) {
                            // Title
                            val titleX = titleStartPx
                            val titleY = (topRowHeight - titleOneLine.size.height) / 2
                            subcompose("TitleSingle") {
                                Text(
                                    text = task.text,
                                    style = titleStyle,
                                    color = textColor,
                                    textDecoration = textDecoration,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }.first().measure(
                                Constraints.fixed(
                                    width = titleOneLine.size.width,
                                    height = titleOneLine.size.height
                                )
                            ).placeRelative(titleX, titleY)

                            // Metadata (to the left of reserved handle area)
                            val metaX = maxW - effectiveHandleWidth - metaCompactWidth
                            val metaY = (topRowHeight - metaCompactHeight) / 2
                            metaCompactPlaceables.forEach { it.placeRelative(metaX, metaY) }

                            // Handle at end (place only when visible)
                            if (showHandle) {
                                val handleX = maxW - handlePlaceWidth
                                val handleY = (topRowHeight - handlePlaceHeight) / 2
                                handlePlaceables.forEach { it.placeRelative(handleX, handleY) }
                            }
                        }
                    } else {
                        // ---- Case B: two tiers ----
                        val metaWrapPlaceables = subcompose("MetaWrap") {
                            MetadataItems(
                                task = task,
                                showCategory = showCategory,
                                category = category,
                                dueLabel = dueLabel,
                                isNotificationPassed = isNotificationPassed,
                                textColor = textColor,
                                compact = false
                            )
                        }.map {
                            it.measure(
                                Constraints(
                                    maxWidth = (maxW - effectiveHandleWidth).coerceAtLeast(0)
                                )
                            )
                        }
                        val metaWrapWidth = metaWrapPlaceables.maxOfOrNull { it.width } ?: 0
                        val metaWrapHeight = metaWrapPlaceables.maxOfOrNull { it.height } ?: 0

                        val titleWrapped: TextLayoutResult = textMeasurer.measure(
                            text = task.text,
                            style = titleStyle,
                            maxLines = Int.MAX_VALUE,
                            overflow = TextOverflow.Clip,
                            constraints = Constraints(maxWidth = maxW - titleStartPx)
                        )
                        val titleWrapHeight = titleWrapped.size.height

                        val topRowHeight = maxOf(metaWrapHeight, handlePlaceHeight)
                        val totalHeight = topRowHeight + tierSpacingPx + titleWrapHeight

                        layout(width = maxW, height = totalHeight) {
                            // Metadata end-aligned in top row
                            val metaX = maxW - effectiveHandleWidth - metaWrapWidth
                            val metaY = (topRowHeight - metaWrapHeight) / 2
                            metaWrapPlaceables.forEach { it.placeRelative(metaX, metaY) }

                            // Handle at end (only if visible)
                            if (showHandle) {
                                val handleX = maxW - handlePlaceWidth
                                val handleY = (topRowHeight - handlePlaceHeight) / 2
                                handlePlaceables.forEach { it.placeRelative(handleX, handleY) }
                            }

                            // Title below
                            val titleX = titleStartPx
                            val titleY = topRowHeight + tierSpacingPx
                            subcompose("TitleWrap") {
                                Text(
                                    text = task.text,
                                    style = titleStyle,
                                    color = textColor,
                                    textDecoration = textDecoration,
                                    softWrap = true,
                                    overflow = TextOverflow.Clip
                                )
                            }.first().measure(
                                Constraints(maxWidth = (maxW - titleStartPx).coerceAtLeast(0))
                            ).placeRelative(titleX, titleY)
                        }
                    }
                }
            }
        }
    }
}

/* ---------- Shared metadata (compact single-line vs wrapping) ---------- */

@Composable
private fun MetadataItems(
    task: Task,
    showCategory: Boolean,
    category: Category,
    dueLabel: String,
    isNotificationPassed: Boolean,
    textColor: Color,
    compact: Boolean
) {
    val iconTint =
        when {
            task.status == TaskStatus.DONE -> Color.Gray
            isNotificationPassed -> textColor.copy(alpha = 0.2f)
            else -> textColor
        }

    val content: @Composable () -> Unit = {
        if (task.reminderTime != null) {
            Icon(
                painter = painterResource(R.drawable.ic_notification),
                contentDescription = stringResource(R.string.notification_heading),
                tint = iconTint,
                modifier = Modifier.size(16.dp)
            )
        }
        if (task.isArchived) {
            ArchivePill(big = false)
        }
        if (showCategory) {
            CategoryPill(category = category, big = false, selected = true)
        }
        if (dueLabel.isNotBlank()) {
            Text(
                text = dueLabel,
                style = MaterialTheme.typography.labelSmall,
                color = if (task.status == TaskStatus.DONE) Color.Gray else textColor
            )
        }
    }

    if (compact) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) { content() }
    } else {
        Column(horizontalAlignment = Alignment.End) {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) { content() }
        }
    }
}
