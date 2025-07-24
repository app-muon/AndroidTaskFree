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
    val routeBase    = currentRoute?.substringBefore("?")
    val bottomBarColour = colorResource(R.color.bottom_bar_colour)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(bottomBarColour) // 👈 fills navigation bar background
            .navigationBarsPadding()
    ) {
        Surface(
            color = bottomBarColour,
            modifier = modifier
                .fillMaxWidth()
                .navigationBarsPadding()           // consumes the real inset
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)                 // Material-3 bottom-bar height
                    .padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically
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
                    val accentBarSelect = when (tab) {
                        BottomTab.Today       -> routeBase == "search"
                        BottomTab.Categories  -> routeBase == "categories"
                    }

                    val solidCol = colorResource(R.color.surface_colour)
                    val accentCol = colorResource(R.color.dialog_primary_colour)
                    val greyCol = solidCol.copy(alpha = 0.7f)

                    val iconCol = when {
                        !hasCategories && tab == BottomTab.Today -> solidCol.copy(alpha = 0.3f)
                        selected                                 -> greyCol
                        else                                     -> solidCol
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
                        /* accent bar pinned to the very top */
                        if (accentBarSelect) {
                            Box(
                                modifier = Modifier
                                    .offset(y = (-8).dp)
                                    .align(Alignment.TopCenter)
                                    .width(24.dp)
                                    .height(2.dp)
                                    .background(accentCol, RoundedCornerShape(percent = 50))
                            )
                        }

                        /* icon + label stack */
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.align(Alignment.Center)
                        ) {
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