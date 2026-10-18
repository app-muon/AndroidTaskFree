package com.taskfree.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import com.taskfree.app.R
import com.taskfree.app.data.entities.Task
import com.taskfree.app.ui.theme.providePanelColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

fun formatTaskDate(date: LocalDate, locale: Locale): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)

fun formatCreationDate(instant: Instant, locale: Locale, zone: ZoneId): String =
    formatTaskDate(instant.atZone(zone).toLocalDate(), locale)

fun formatDateTime(instant: Instant, locale: Locale, zone: ZoneId): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
        .format(instant.atZone(zone))

/**
 * Both dates on one line, each after its heading, when that fits. Otherwise two equally sized
 * columns, each with its heading above its date.
 */
@Composable
fun TaskDatesRow(task: Task) {
    val colors = providePanelColors()
    val locale = LocalConfiguration.current.locales[0]
    val zone = ZoneId.systemDefault()
    val created = remember(task.originalCreatedAt, locale, zone) {
        task.originalCreatedAt?.let { formatCreationDate(it, locale, zone) }
    } ?: stringResource(R.string.creation_date_not_recorded)
    val completed = remember(task.completedDate, locale) {
        task.completedDate?.let { formatTaskDate(it, locale) }
    } ?: stringResource(R.string.completed_no_label)

    Layout(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = PanelConstants.HORIZONTAL_PADDING,
            vertical = PanelConstants.VERTICAL_PADDING
        ),
        content = {
            TaskFieldHeading(stringResource(R.string.created_date_label))
            Text(created, style = MaterialTheme.typography.bodyMedium, color = colors.surfaceText)
            TaskFieldHeading(stringResource(R.string.completed_date_label))
            Text(completed, style = MaterialTheme.typography.bodyMedium, color = colors.surfaceText)
        }
    ) { measurables, constraints ->
        val headingGap = PanelConstants.SPACER_WIDTH.roundToPx()
        val datesGap = PanelConstants.HORIZONTAL_PADDING.roundToPx()
        val oneLineWidth = measurables.sumOf { it.maxIntrinsicWidth(Constraints.Infinity) } +
            2 * headingGap + datesGap
        if (oneLineWidth <= constraints.maxWidth) {
            // Headings and dates share a baseline, as their text sizes differ.
            val placeables = measurables.map { it.measure(Constraints()) }
            val baseline = placeables.maxOf { it[FirstBaseline] }
            val height = placeables.maxOf { baseline - it[FirstBaseline] + it.height }
            layout(constraints.maxWidth, height) {
                var x = 0
                placeables.forEachIndexed { i, placeable ->
                    placeable.placeRelative(x, baseline - placeable[FirstBaseline])
                    x += placeable.width + if (i == 1) datesGap else headingGap
                }
            }
        } else {
            val spacing = PanelConstants.CHIP_SPACING.roundToPx()
            val columnWidth = (constraints.maxWidth - spacing) / 2
            val (createdHeading, createdDate, completedHeading, completedDate) =
                measurables.map { it.measure(Constraints(maxWidth = columnWidth)) }
            val height = maxOf(createdHeading.height + createdDate.height,
                completedHeading.height + completedDate.height)
            layout(constraints.maxWidth, height) {
                createdHeading.placeRelative(0, 0)
                createdDate.placeRelative(0, createdHeading.height)
                completedHeading.placeRelative(columnWidth + spacing, 0)
                completedDate.placeRelative(columnWidth + spacing, completedHeading.height)
            }
        }
    }
}
