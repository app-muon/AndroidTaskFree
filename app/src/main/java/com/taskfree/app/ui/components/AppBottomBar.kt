// ui/AppBottomBar.kt
package com.taskfree.app.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.taskfree.app.R

@Composable
fun AppBottomBar(
    navController: NavController,
    modifier: Modifier = Modifier,
    isTodayView: Boolean,
    hasCategories: Boolean,
    addButtonLabel: String,
    onAddTask: () -> Unit = {},
    onShowGlobalMenu: () -> Unit,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Colors/strings (safe to read inside a composable)
    val bottomBarColour = colorResource(R.color.bottom_bar_colour)
    val surfaceCol = colorResource(R.color.surface_colour)
    val toolsLabel = stringResource(R.string.tools_menu_name)

    // Nav tab styling
    val tabShape = RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomEnd = 12.dp, bottomStart = 12.dp)
    val tabIconActive = surfaceCol
    val tabIconInactive = tabIconActive.copy(alpha = 0.74f)
    val tabBgSelected = colorResource(R.color.list_background_colour)
    val tabBgUnselected = tabBgSelected.copy(alpha = 0.22f)

    // Reserve width for the longest Add label (text + icon + spacer + padding + small buffer)
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall
    val longest = stringResource(R.string.add_category_button_label)
    val textPx = textMeasurer.measure(longest, style = labelStyle).size.width
    val density = LocalDensity.current
    val reservedWidth = with(density) {
        textPx.toDp() +
                18.dp +            // icon size in Add button
                6.dp +             // spacer between icon and text
                24.dp +            // horizontal content padding (12 + 12)
                2.dp               // tiny safety buffer
    }

    Surface(color = bottomBarColour, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                .padding(top = 0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Tools (left) — cog + label, vertically aligned with nav tabs
            Box(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .clickable { onShowGlobalMenu() }
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = toolsLabel,
                        tint = surfaceCol,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(toolsLabel, style = MaterialTheme.typography.labelSmall, color = surfaceCol)
                }
            }

            // NAV CAPSULE (center)
            Box(
                modifier = Modifier
                    .weight(2f)
                    .padding(horizontal = 8.dp)
                    .padding(horizontal = 4.dp, vertical = 0.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BottomTab.entries.forEach { tab ->
                        val selected = when (tab) {
                            BottomTab.Today -> isTodayView
                            BottomTab.Categories -> currentRoute == tab.route
                        }
                        val iconCol = if (selected) tabIconActive else tabIconInactive

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(if (selected) tabBgSelected else tabBgUnselected, tabShape)
                                .clickable(enabled = !selected && (tab != BottomTab.Today || hasCategories)) {
                                    navController.popBackStack()
                                    when (tab) {
                                        BottomTab.Today -> navController.navigate("search?categoryId=-1&dateOffset=0") { launchSingleTop = true }
                                        BottomTab.Categories -> navController.navigate(tab.route) { launchSingleTop = true }
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = stringResource(tab.label),
                                    tint = iconCol,
                                    modifier = Modifier.size(22.dp)
                                )
                                Text(
                                    stringResource(tab.label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = iconCol
                                )
                            }
                        }
                    }
                }
            }

            // Add (right) — primary action button with fixed width
            BottomBarActionButton(
                onClick = onAddTask,
                label = addButtonLabel,
                icon = Icons.Default.Add,
                containerColor = colorResource(R.color.pill_colour),
                contentColor = colorResource(R.color.pill_text),
                width = reservedWidth,
                modifier = Modifier.padding(end = 12.dp)
            )
        }
    }
}

private enum class BottomTab(
    @StringRes val label: Int,
    val route: String,
    val icon: ImageVector
) {
    Categories(R.string.categories, "categories", Icons.Outlined.Folder),
    Today(R.string.tasks_due, "search", Icons.Outlined.Today)
}

@Composable
fun BottomBarActionButton(
    onClick: () -> Unit,
    label: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    width: Dp,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.width(width),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
