// DragHandle.kt

/**
 * A reusable composable for the three-bar “handle”.
 */
package com.taskfree.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import com.taskfree.app.R

@Composable
fun DragHandle(
    modifier: Modifier = Modifier, show: Boolean = true, icon: ImageVector = Icons.Default.Menu
) {
    Box(
        modifier = modifier.requiredSize(32.dp), contentAlignment = Alignment.Center
    ) {
        if (show) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.requiredSize(24.dp),
                tint = colorResource(R.color.surface_colour)
            )
        }
    }
}
