// ui/task/components/SortMode.kt
package com.taskfree.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.ui.graphics.vector.ImageVector

enum class SortMode(val icon: ImageVector) {
    USER(Icons.Default.Menu), DATE_ASC(Icons.Default.KeyboardArrowUp), DATE_DESC(Icons.Default.KeyboardArrowDown);

    fun next(): SortMode = when (this) {
        USER -> DATE_ASC
        DATE_ASC -> DATE_DESC
        DATE_DESC -> USER
    }
}
