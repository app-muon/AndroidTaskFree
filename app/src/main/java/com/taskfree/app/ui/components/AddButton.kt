package com.taskfree.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import com.taskfree.app.R


@Composable
fun AddButton(
    onClick: () -> Unit, buttonLabel: String, enabled: Boolean = true, modifier: Modifier = Modifier
) {
    val alpha = if (enabled) 1f else 0.4f

    Column(
        modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally
    ) {    // ← NEW wrapper
        /* --- circular icon button --- */
        Surface(shape = CircleShape,
            color = colorResource(R.color.add_task_button_background_colour),
            tonalElevation = 2.dp,
            modifier = Modifier
                .size(24.dp)
                .alpha(alpha)
                .let { if (enabled) it.clickable(onClick = onClick) else it }) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = buttonLabel,
                    tint = colorResource(R.color.surface_colour),
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        /* --- text label --- */
        Text(
            text = buttonLabel,
            style = MaterialTheme.typography.labelSmall,
            color = colorResource(R.color.surface_colour),
            modifier = Modifier
                .alpha(alpha)
                .padding(top = 2.dp)
        )
    }
}
