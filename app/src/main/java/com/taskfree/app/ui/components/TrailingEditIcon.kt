// ui/task/components/TrailingEditIcon.kt
package com.taskfree.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import com.taskfree.app.R

@Composable
fun TrailingEditIcon(onClick: () -> Unit) {
    Icon(
        imageVector = Icons.Default.Edit,
        contentDescription = null,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(start = 8.dp)
            .scale(0.8f),
        tint = colorResource(R.color.surface_colour)
    )
}