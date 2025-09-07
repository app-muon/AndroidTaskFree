// ui/task/components/TaskSearchFilters.kt
package com.taskfree.app.ui.task.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.taskfree.app.R
import com.taskfree.app.data.entities.Category
import com.taskfree.app.ui.components.CategoryPill
import com.taskfree.app.ui.components.DueChoice
import com.taskfree.app.ui.components.InfoPill
import com.taskfree.app.ui.components.LabelledOptionPill
import com.taskfree.app.ui.components.choiceLabel
import com.taskfree.app.ui.components.isSameKindAs
import com.taskfree.app.ui.components.launchDatePicker
import com.taskfree.app.ui.components.showDatePicker

@Composable
private fun CompactDropdownItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 5.dp), // tight spacing
        verticalAlignment = Alignment.CenterVertically
    ) {
        content()
    }
}

@Composable
private fun menuCap90(): Dp {
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val hPx = windowInfo.containerSize.height     // px
    return with(density) { (hPx * 0.9f).toDp() }  // 90% of window height in dp
}

/**
 * Compact row that hosts the **Category** and **Date** dropdown filters
 * shown in the Task screen’s top bar.
 */
@Composable
fun TaskSearchFilters(
    categories: List<Category>,
    selectedCatId: Int?,
    onSelectCategory: (Int?) -> Unit,
    selectedDueChoice: DueChoice,
    onDueSelected: (DueChoice) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box {
            CategoryDropDown(
                allCats = categories, selectedId = selectedCatId, onSelected = onSelectCategory
            )
        }
        Box {
            DateDropDown(
                selectedDueChoice = selectedDueChoice, onDueSelected = onDueSelected
            )
        }
    }
}

/* -------------------------------------------------------------------------
   Below are the two small dropdown helpers moved out of TaskSearchScreen.
   If you already extracted these elsewhere, delete one copy and import the other.
   ------------------------------------------------------------------------- */

@Composable
fun CategoryDropDown(
    allCats: List<Category>, selectedId: Int?, onSelected: (Int?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val cap = menuCap90()
    val selectedCat = allCats.firstOrNull { it.id == selectedId }
    val label = selectedCat?.title ?: stringResource(R.string.all_categories)
    Box(modifier = Modifier.clickable { expanded = true }) {
        if (selectedCat == null) {
            InfoPill(
                label, colorResource(R.color.all_category_pill_colour), big = true, border = true
            )
        } else {
            CategoryPill(category = selectedCat, big = true, selected = true)
        }
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        modifier = Modifier
            .zIndex(2f)
            .background(colorResource(R.color.dialog_background_colour))
            .heightIn(max = cap)
    ) {
        CompactDropdownItem(onClick = { onSelected(null); expanded = false }) {
            InfoPill(
                stringResource(R.string.all_categories),
                colorResource(R.color.all_category_pill_colour),
                big = true,
                border = selectedCat != null,
                selected = selectedCat == null
            )
        }
        allCats.forEach { cat ->
            CompactDropdownItem(onClick = { onSelected(cat.id); expanded = false }) {
                CategoryPill(cat, big = true, selected = selectedCat == cat)
            }
        }
    }
}

@Composable
fun DateDropDown(
    selectedDueChoice: DueChoice, onDueSelected: (DueChoice) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val cap = menuCap90()
    val context = LocalContext.current

    Box(modifier = Modifier.clickable { expanded = true }) {
        LabelledOptionPill(label = selectedDueChoice.choiceLabel(), selected = true, big = true)
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        modifier = Modifier
            .zIndex(3f)
            .background(colorResource(R.color.dialog_background_colour))
            .heightIn(max = cap)
    ) {

        /** one reusable dropdown row */
        @Composable
        fun Item(entry: DueChoice) = CompactDropdownItem(
            onClick = {
                expanded = false
                if (entry.launchDatePicker()) {
                    showDatePicker(context, entry.date) { picked ->
                        onDueSelected(DueChoice.from(picked))
                    }
                } else onDueSelected(entry)
            }
        ) {
            LabelledOptionPill(
                label = entry.choiceLabel(),
                big = true,
                selected = selectedDueChoice isSameKindAs entry
            )
        }
        DueChoice.allFilters().forEach { Item(it) }
    }
}