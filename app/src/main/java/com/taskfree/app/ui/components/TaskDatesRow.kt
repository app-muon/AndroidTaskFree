package com.taskfree.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
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

/** Two equally sized date fields keep both dates on the same row. */
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

    Row(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = PanelConstants.HORIZONTAL_PADDING,
            vertical = PanelConstants.VERTICAL_PADDING
        ),
        horizontalArrangement = Arrangement.spacedBy(PanelConstants.CHIP_SPACING)
    ) {
        Column(Modifier.weight(1f)) {
            TaskFieldHeading(stringResource(R.string.created_date_label))
            Text(created, style = MaterialTheme.typography.bodyMedium, color = colors.surfaceText)
        }
        Column(Modifier.weight(1f)) {
            TaskFieldHeading(stringResource(R.string.completed_date_label))
            Text(completed, style = MaterialTheme.typography.bodyMedium, color = colors.surfaceText)
        }
    }
}
