package com.taskfree.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.taskfree.app.R
import com.taskfree.app.data.entities.Category
import com.taskfree.app.domain.model.Recurrence
import com.taskfree.app.domain.model.label

@Composable
fun CategoryPill(
    category: Category,
    big: Boolean = false,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    InfoPill(
        title = category.title,
        selectedFillColor = Color(category.color),
        border = !selected,
        big = big,
        selected = selected,
        onClick = onClick
    )
}

@Composable
fun ArchivePill(
    big: Boolean = false,
) {
    InfoPill(
        title = stringResource(R.string.archived_task_label),
        selectedFillColor = Color.Gray,
        big = big,
    )
}

@Composable
fun LabelledOptionPill(
    label: String,
    big: Boolean = false,
    selected: Boolean = false,
    error: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    InfoPill(
        title = label,
        selectedFillColor = if (error) Color.Red.copy(alpha = 0.8f) else colorResource(R.color.pill_colour),
        selectedTextColor = colorResource(R.color.pill_text),
        border = !selected,
        big = big,
        selected = selected,
        onClick = onClick
    )
}

@Composable
fun RecurrencePill(
    recurrence: Recurrence,
    terse: Boolean = false,
    big: Boolean = false,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null
) {

    val recurrenceText = if (terse && recurrence == Recurrence.NONE) {
        ""
    } else {
        recurrence.label()
    }
    InfoPill(
        title = recurrenceText,
        selectedFillColor = colorResource(R.color.pill_colour),
        selectedTextColor = colorResource(R.color.pill_text),
        border = !selected,
        big = big,
        selected = selected,
        onClick = onClick
    )
}

@Composable
fun InfoPill(
    title: String,
    selectedFillColor: Color,
    modifier: Modifier = Modifier,
    selectedTextColor: Color = Color.White,
    unselectedFillColor: Color = colorResource(R.color.pill_text),
    unselectedTextColor: Color = selectedFillColor,
    big: Boolean = false,
    border: Boolean = false,
    selected: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val pillStyle =
        if (big) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall
    val pillHorizontalPad = if (big) 12.dp else 6.dp
    val pillVerticalPad = if (big) 6.dp else 2.dp
    val pillRound = if (big) 12.dp else 8.dp
    val useFillColor = if (selected) selectedFillColor else unselectedFillColor
    val userTextColor = if (selected) selectedTextColor else unselectedTextColor.copy(alpha = 0.8f)
    Surface(
        color = useFillColor,
        shape = RoundedCornerShape(pillRound),
        modifier = modifier
            .padding(0.dp)
            .then(if (onClick != null) {
                Modifier.clickable { onClick() }
            } else {
                Modifier
            }),
        border = if (border) BorderStroke(
            1.dp, selectedFillColor.copy(alpha = 0.3f)
        ) else null,

        ) {
        Text(
            text = title,
            style = pillStyle,
            color = userTextColor,
            modifier = Modifier.padding(horizontal = pillHorizontalPad, vertical = pillVerticalPad)
        )
    }
}
