// CategoryEmptyState.kt
package com.taskfree.app.ui.category.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import com.taskfree.app.R

@Composable
internal fun ColumnScope.CategoryEmptyState() = Box(
    Modifier
        .weight(1f)
        .fillMaxWidth(), contentAlignment = Alignment.Center
) {
    Text(
        stringResource(R.string.no_categories_yet), color = colorResource(R.color.surface_colour)
    )
}
