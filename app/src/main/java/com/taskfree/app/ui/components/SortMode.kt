// ui/task/components/SortMode.kt
package com.taskfree.app.ui.components

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taskfree.app.R
import java.text.Collator

/** Each mode is shown above the handle column as an icon or, for alphabetical order, a letter. */
enum class SortMode(
    @StringRes val messageRes: Int, val icon: ImageVector? = null, val letter: String? = null
) {
    USER(R.string.sort_order_custom, Icons.Default.Menu),
    DATE_ASC(R.string.sort_order_date_asc, Icons.Default.KeyboardArrowUp),
    DATE_DESC(R.string.sort_order_date_desc, Icons.Default.KeyboardArrowDown),
    ALPHA_ASC(R.string.sort_order_alpha_asc, letter = "A"),
    ALPHA_DESC(R.string.sort_order_alpha_desc, letter = "Z");

    /** The mode after this one in [modes], wrapping round to the first. */
    fun next(modes: List<SortMode> = entries): SortMode =
        modes[(modes.indexOf(this) + 1) % modes.size]

    companion object {
        /** Categories have no dates, so they only cycle between custom and alphabetical order. */
        val CATEGORY_SORT_MODES = listOf(USER, ALPHA_ASC, ALPHA_DESC)
    }
}

/** Sorts by [text] in the device language's order, so case and accents don't push names to the end. */
fun <T> List<T>.sortedAlphabetically(descending: Boolean, text: (T) -> String): List<T> {
    val byText = compareBy(Collator.getInstance(), text)
    return sortedWith(if (descending) byText.reversed() else byText)
}

/** The sort label above the handle column; a tap moves to the next mode in [modes] and toasts its name. */
@Composable
fun SortToggle(
    mode: SortMode,
    onChange: (SortMode) -> Unit,
    modifier: Modifier = Modifier,
    modes: List<SortMode> = SortMode.entries
) {
    val context = LocalContext.current
    val description = stringResource(mode.messageRes)
    val clickModifier = modifier
        .semantics { contentDescription = description }
        .clickable {
            val next = mode.next(modes)
            onChange(next)
            Toast.makeText(context, context.getString(next.messageRes), Toast.LENGTH_SHORT).show()
        }

    if (mode.icon != null) {
        DragHandle(icon = mode.icon, modifier = clickModifier)
    } else {
        Box(modifier = clickModifier.requiredSize(32.dp), contentAlignment = Alignment.Center) {
            Text(
                text = mode.letter.orEmpty(),
                color = colorResource(R.color.surface_colour),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
