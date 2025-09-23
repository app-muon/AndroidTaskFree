// ui/TabShapes.kt
package com.taskfree.app.ui.components

import androidx.compose.foundation.shape.GenericShape

/**
 * Convex-top tab shape:
 * - flat bottom
 * - top bulges out (above 0)
 */
val TabShape = GenericShape { size, _ ->
    val w = size.width
    val h = size.height
    val bulge = 12f   // how far the top arches outward

    moveTo(0f, h)          // bottom-left
    lineTo(0f, 0f)         // straight up left edge

    // top edge with outward convex curve
    quadraticBezierTo(w / 2f, -bulge, w.toFloat(), 0f)

    lineTo(w.toFloat(), h) // down right edge
    close()
}
