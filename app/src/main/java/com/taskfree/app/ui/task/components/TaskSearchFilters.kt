// ui/task/components/TaskSearchFilters.kt
package com.taskfree.app.ui.task.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.taskfree.app.R
import com.taskfree.app.data.entities.Category
import com.taskfree.app.ui.components.CategoryPill
import com.taskfree.app.ui.components.LabelledOptionPill
import com.taskfree.app.ui.components.DueChoice
import com.taskfree.app.ui.components.InfoPill
import com.taskfree.app.ui.components.choiceLabel
import com.taskfree.app.ui.components.isSameKindAs
import com.taskfree.app.ui.components.launchDatePicker
import com.taskfree.app.ui.components.showDatePicker
import java.time.LocalDate

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
    val selectedCat = allCats.firstOrNull { it.id == selectedId }
    val label = selectedCat?.title ?: stringResource(R.string.all_categories)
    TextButton(
        onClick = { expanded = true },
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
    ) {
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
    ) {
        DropdownMenuItem(
            text = {
                InfoPill(
                    stringResource(R.string.all_categories),
                    colorResource(R.color.all_category_pill_colour),
                    big = true,
                    border = selectedCat != null,
                    selected = selectedCat == null
                )
            },
            onClick = { onSelected(null); expanded = false },
            modifier = Modifier.height(32.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 1.dp)
        )
        allCats.forEach { cat ->
            DropdownMenuItem(
                text = {
                    CategoryPill(cat, big = true, selected = selectedCat == cat)
                },
                onClick = { onSelected(cat.id); expanded = false },
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 1.dp)
            )
        }
    }
}

@Composable
fun DateDropDown(
    selectedDueChoice: DueChoice, onDueSelected: (DueChoice) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val context = LocalContext.current

    TextButton(
        onClick = { expanded = true },
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
    ) {
        LabelledOptionPill(label = selectedDueChoice.choiceLabel(), selected = true, big = true)
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        modifier = Modifier
            .zIndex(3f)
            .background(colorResource(R.color.dialog_background_colour))
    ) {

        /** one reusable dropdown row */
        @Composable
        fun Item(entry: DueChoice) {
            DropdownMenuItem(
                text = {
                    LabelledOptionPill(
                        label = entry.choiceLabel(),
                        big = true,
                        selected = selectedDueChoice isSameKindAs entry
                    )
                },
                onClick = {
                    expanded = false
                    if (entry.launchDatePicker()) {
                        showDatePicker(
                            context = context, initialDate = entry.date ?: today
                        ) { picked ->
                            onDueSelected(DueChoice.from(picked))
                        }
                    } else {
                        onDueSelected(entry)
                    }
                },
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 1.dp)
            )
        }

        /* populate menu */
        DueChoice.allFilters().forEach { Item(it) }
    }
}