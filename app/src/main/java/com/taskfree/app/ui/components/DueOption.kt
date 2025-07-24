package com.taskfree.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.taskfree.app.R
import com.taskfree.app.data.entities.Task
import java.time.LocalDate

enum class DueKind { NONE, ALL, TODAY, TOMORROW, PLUS2, OTHER }/*─────────────────────────────── 1 ▌MODEL ────────────────────────────────*/

sealed class DueChoice {
    /** Small enum to tag the two "no-date" options */
    enum class Special { NONE, ALL }

    /** Optional concrete date (only meaningful for Today / Tomorrow / … / Other) */
    abstract val date: LocalDate?

    /*── Variants ─────────────────────────────────────────────────────────*/

    data object None : DueChoice() {
        override val date: LocalDate? = null
    }

    data object All : DueChoice() {
        override val date: LocalDate? = null
    }

    data object Today : DueChoice() {
        override val date: LocalDate? = LocalDate.now()
    }

    data object Tomorrow : DueChoice() {
        override val date: LocalDate? = LocalDate.now().plusDays(1)
    }

    data object Plus2 : DueChoice() {
        override val date: LocalDate? = LocalDate.now().plusDays(2)
    }

    data class Other(override val date: LocalDate?) :
        DueChoice()          // may be null (picker not chosen)

    /*────────────────────────── factory ────────────────────────────────*/
    companion object {

        /**
         * Convert a nullable LocalDate **or** special token:
         *   - `null`   → None
         *   - *today*  → Today
         *   - *+1 day* → Tomorrow
         *   - *+2 day* → Plus2
         *   - anything else → Other(date)
         */
        fun from(date: LocalDate): DueChoice {
            val today = LocalDate.now()
            return when (date) {
                today -> Today
                today.plusDays(1) -> Tomorrow
                today.plusDays(2) -> Plus2
                else -> Other(date)
            }
        }

        fun fromSpecial(token: Special): DueChoice = when (token) {
            Special.NONE -> None
            Special.ALL -> All
        }

        /** Preset list for choice UI (order matters) */
        fun allChoices(): List<DueChoice> = listOf(None, Today, Tomorrow, Plus2, Other(null))

        /** Preset list for filter UI (adds the "All" entry) */
        fun allFilters(): List<DueChoice> = listOf(All, Today, Tomorrow, Plus2, Other(null))

    }
}/*──────────────────────────── 2 ▌BEHAVIOUR ──────────────────────────────*/

infix fun DueChoice.isSameKindAs(other: DueChoice): Boolean = this::class == other::class

val DueChoice.kind: DueKind
    get() = when (this) {
        DueChoice.None -> DueKind.NONE
        DueChoice.All -> DueKind.ALL
        DueChoice.Today -> DueKind.TODAY
        DueChoice.Tomorrow -> DueKind.TOMORROW
        DueChoice.Plus2 -> DueKind.PLUS2
        is DueChoice.Other -> DueKind.OTHER
    }

@Composable
fun DueChoice.choiceLabel(): String = when (this) {
    DueChoice.None -> stringResource(R.string.no_due_date_due_value)
    DueChoice.All -> stringResource(R.string.all_dates)
    DueChoice.Today -> stringResource(R.string.today)
    DueChoice.Tomorrow -> stringResource(R.string.tomorrow)
    DueChoice.Plus2 -> stringResource(R.string.today_offset, 2)
    is DueChoice.Other -> stringResource(R.string.date_picker)          // always says "Pick date…"
}


@Composable
fun DueChoice.resultLabel(): String {
    val context = LocalContext.current
    return when (this) {
        DueChoice.None, DueChoice.All, DueChoice.Today, DueChoice.Tomorrow, DueChoice.Plus2 -> choiceLabel()              // identical for these
        is DueChoice.Other ->                             // show real date if available
            date?.let { specificDateLabel(it, context = context) }
                ?: stringResource(R.string.date_picker)
    }
}

/** True ⇢ UI should launch a date-picker */
fun DueChoice.launchDatePicker(): Boolean = this is DueChoice.Other


/*──────────────────────────── 3 ▌LOCAL LABEL HELPERS ────────────────────*/


val DueChoiceSaver: Saver<DueChoice, List<Any?>> = Saver(save = { choice ->
    listOf(
        choice.kind.name,            // String tag  – index 0
        choice.date?.toString()      // ISO String? – index 1
    )
}, restore = { saved ->
    val kindName = saved[0] as String
    val iso = saved[1] as String?

    when (kindName) {
        "NONE" -> DueChoice.None
        "ALL" -> DueChoice.All
        "TODAY" -> DueChoice.Today
        "TOMORROW" -> DueChoice.Tomorrow
        "PLUS2" -> DueChoice.Plus2
        "OTHER" -> DueChoice.Other(iso?.let(LocalDate::parse))
        else -> DueChoice.None      // fallback
    }
})

fun DueChoice.Companion.fromTask(task: Task): DueChoice {
    return task.due?.let { from(it) } ?: fromSpecial(DueChoice.Special.NONE)
}