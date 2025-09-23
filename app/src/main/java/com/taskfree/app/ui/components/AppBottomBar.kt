// ui/AppBottomBar.kt
package com.taskfree.app.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.taskfree.app.R
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars

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
    val bottomBarColour = colorResource(R.color.bottom_bar_colour)
    val tabShape = RoundedCornerShape(12.dp)
    val tabIconActive   = colorResource(R.color.surface_colour)
    val tabIconInactive = tabIconActive.copy(alpha = 0.74f)
    val tabBgSelected   = tabIconActive.copy(alpha = 0.12f)   // subtle filled tab
    val tabBorder       = tabIconActive.copy(alpha = 0.22f)   // for unselected outline

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(bottomBarColour) // 👈 fills navigation bar background
    ) {
        Surface(
            color = bottomBarColour,
            modifier = modifier
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)                 // Material-3 bottom-bar height
                    .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                    .padding(top = 4.dp),  // slightly less top padding to balance
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .weight(1f)
                        .clickable { onShowGlobalMenu() }) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = stringResource(R.string.tools_menu_name),
                        tint = colorResource(R.color.surface_colour),
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = stringResource(R.string.tools_menu_name),
                        style = MaterialTheme.typography.labelSmall,
                        color = colorResource(R.color.surface_colour)
                    )
                }

                /* --- inside the Row { … } ───────── replace the old BottomTab loop --- */

                BottomTab.entries.forEach { tab ->

                    val selected = when (tab) {
                        BottomTab.Today -> isTodayView
                        BottomTab.Categories -> currentRoute == tab.route
                    }

                    val iconCol = when {
                        !hasCategories && tab == BottomTab.Today -> tabIconInactive.copy(alpha = 0.4f)
                        selected                                 -> tabIconActive
                        else                                     -> tabIconInactive
                    }

                    Box(                                           // owns full width of slot
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = !selected && (tab != BottomTab.Today || hasCategories)) {
                                if (!selected) {
                                    if (tab == BottomTab.Today && !hasCategories) return@clickable
                                    navController.popBackStack()
                                    when (tab) {
                                        BottomTab.Today -> navController.navigate("search?categoryId=-1&dateOffset=0") {
                                            launchSingleTop = true
                                        }

                                        BottomTab.Categories -> navController.navigate(tab.route) {
                                            launchSingleTop = true
                                        }
                                    }
                                }
                            }
                            .padding(vertical = 2.dp)) {


                        /* tab pill container + icon/label */
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 6.dp)
                                .clip(tabShape)
                                .then(
                                    if (selected) Modifier.background(tabBgSelected)
                                    else Modifier.border(1.dp, tabBorder, tabShape)
                                )
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = stringResource(tab.label),
                                    tint = iconCol,
                                    modifier = Modifier.size(24.dp)
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
                AddButton(
                    onClick = onAddTask,
                    buttonLabel = addButtonLabel,
                    enabled = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

private enum class BottomTab(
    @StringRes val label: Int, val route: String, val icon: ImageVector
) {
    Categories(
        R.string.categories,
        "categories",
        Icons.Outlined.Folder
    ),
    Today(R.string.tasks_due, "search", Icons.Outlined.Today)
}