package com.taskfree.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn

private const val TABLET_SW_DP = 600
private const val TABLET_FRACTION = 0.8f

@Composable
fun Modifier.dialogResponsiveWidth(
    tabletSwDp: Int = TABLET_SW_DP,
    tabletFraction: Float = TABLET_FRACTION,
    maxWidthCap: Dp = 720.dp
): Modifier {
    val sw = LocalConfiguration.current.smallestScreenWidthDp
    val fraction = if (sw >= tabletSwDp) tabletFraction else 1f
    return this
        .fillMaxWidth(fraction)
        .widthIn(max = maxWidthCap)
}

@Composable
fun Modifier.dialogMaxHeight(
    fraction: Float = 0.92f
): Modifier {
    val wi = LocalWindowInfo.current
    val cfg = LocalConfiguration.current
    val density = LocalDensity.current

    val containerHdp = with(density) { wi.containerSize.height.toDp() }
    val base = if (containerHdp > 0.dp) containerHdp else cfg.screenHeightDp.dp
    return this.heightIn(max = base * fraction)
}
