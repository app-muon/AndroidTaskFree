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
    val tabShape = RoundedCornerShape(
        topStart = 0.dp, topEnd = 0.dp, bottomEnd = 12.dp, bottomStart = 12.dp
    )
    val bottomBarColour = colorResource(R.color.bottom_bar_colour)

    val tabIconActive = colorResource(R.color.surface_colour)
    val tabIconInactive = tabIconActive.copy(alpha = 0.74f)
    val tabBgSelected = colorResource(R.color.list_background_colour)
    val tabBgUnselected = tabBgSelected.copy(alpha = 0.22f)
    val actionButtonShape = RoundedCornerShape(16.dp)
    val actionButtonBorder = tabIconActive.copy(alpha = 0.4f)
    val textMeasurer = rememberTextMeasurer()
    val longest = stringResource(R.string.add_category_button_label)
    val labelStyle = MaterialTheme.typography.labelSmall

    val longestWidthPx = textMeasurer.measure(longest, style = labelStyle).size.width
    val reservedWidth = with(LocalDensity.current) { longestWidthPx.toDp() + 24.dp }
    Surface(
        color = bottomBarColour, modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                .padding(top = 0.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            BottomBarActionButton(
                onClick = onShowGlobalMenu,
                label = stringResource(R.string.tools_menu_name),
                icon = Icons.Default.Settings,
                containerColor = Color.Transparent,
                contentColor = colorResource(R.color.surface_colour),
                width = reservedWidth,
                modifier = Modifier.padding(start = 12.dp)
            )

            // NAV CAPSULE (center) – does NOT clip children
            Box(
                modifier = Modifier
                    .weight(2f)
                    .padding(horizontal = 8.dp)
                    .background(
                        color = Color.Transparent,            // or colorResource(R.color.pill_colour)
                        shape = RoundedCornerShape(16.dp)     // background shape only; no clipping
                    )
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
                                .then(
                                    if (selected) {
                                        Modifier.background(tabBgSelected, tabShape)
                                    } else {
                                        Modifier.background(tabBgUnselected, tabShape)
                                    }
                                )
                                .clickable(enabled = !selected) {
                                    navController.popBackStack()
                                    when (tab) {
                                        BottomTab.Today -> if (hasCategories) {
                                            navController.navigate("search?categoryId=-1&dateOffset=0") {
                                                launchSingleTop = true
                                            }
                                        }

                                        BottomTab.Categories -> {
                                            navController.navigate(tab.route) {
                                                launchSingleTop = true
                                            }
                                        }
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center) {
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

            BottomBarActionButton(
                onClick = onAddTask,
                label = addButtonLabel,
                icon = Icons.Default.Add,
                containerColor = colorResource(R.color.add_task_or_category_pill),
                contentColor = colorResource(R.color.pill_text),
                width = reservedWidth,
                modifier = Modifier.padding(end = 12.dp)
            )


        }
    }
}

private enum class BottomTab(
    @StringRes val label: Int, val route: String, val icon: ImageVector
) {
    Categories(
        R.string.categories, "categories", Icons.Outlined.Folder
    ),
    Today(
        R.string.tasks_due, "search", Icons.Outlined.Today
    )
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
            containerColor = containerColor, contentColor = contentColor
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
