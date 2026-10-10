// ui/admin/ArchiveRepeatsDialog.kt
package com.taskfree.app.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.taskfree.app.R
import com.taskfree.app.data.entities.Category
import com.taskfree.app.data.repository.CompletedRepeatsPreview
import com.taskfree.app.ui.components.CategoryPill
import com.taskfree.app.ui.components.ConfirmArchive
import com.taskfree.app.ui.components.ConfirmDialog

/** Confirmation for "Archive old completed repeats", listing what will be archived. */
@Composable
internal fun ArchiveRepeatsDialog(
    preview: CompletedRepeatsPreview,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (preview.groups.isEmpty()) {
        ConfirmDialog(
            title = stringResource(R.string.archive_old_completed_repeats),
            message = stringResource(R.string.archive_repeats_none),
            yesMessage = stringResource(R.string.close),
            yesColour = colorResource(R.color.dialog_button_text_colour),
            noMessage = "",
            onYes = onDismiss,
            onNo = onDismiss
        )
        return
    }

    ConfirmArchive(
        title = stringResource(R.string.confirm_archive_repeats_title),
        message = stringResource(R.string.confirm_archive_repeats_msg),
        onYes = onConfirm,
        onNo = onDismiss
    ) {
        CompletedRepeatsList(preview, Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun CompletedRepeatsList(preview: CompletedRepeatsPreview, modifier: Modifier = Modifier) {
    val textColour = colorResource(R.color.surface_colour)
    val lineColour = textColour.copy(alpha = 0.15f)
    val shape = RoundedCornerShape(12.dp)

    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, lineColour, shape)
    ) {
        preview.groups.forEach { group ->
            CountRow(count = group.count, textColour = textColour) {
                Text(
                    text = group.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColour,
                    modifier = Modifier.weight(1f)
                )
                CategoryPill(
                    category = Category(
                        id = group.categoryId, title = group.catTitle, color = group.catColor
                    ),
                    selected = true
                )
            }
            HorizontalDivider(color = lineColour)
        }
        CountRow(
            count = preview.total,
            textColour = textColour,
            bold = true,
            modifier = Modifier.background(textColour.copy(alpha = 0.05f))
        ) {
            Text(
                text = stringResource(R.string.archive_repeats_total),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = textColour,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** One line of the list: [label] on the left, [count] right-aligned so the numbers line up. */
@Composable
private fun CountRow(
    count: Int,
    textColour: Color,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
    label: @Composable RowScope.() -> Unit
) {
    Row(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        label()
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
            color = textColour,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 32.dp)
        )
    }
}
