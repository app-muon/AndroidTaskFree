// showDatePicker.kt
package com.taskfree.app.ui.components

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.fragment.app.FragmentActivity
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.taskfree.app.R
import com.taskfree.app.util.AppDateProvider
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Shows a Material Date Picker with the custom theme
 * @param context The context to show the picker in
 * @param initialDate The initial date to select (defaults to today if null)
 * @param onDateSelected Callback when a date is selected
 */
fun showDatePicker(
    context: Context,
    initialDate: LocalDate? = null,
    minDate: LocalDate? = null,
    onDateSelected: (LocalDate) -> Unit
) {
    val dp = AppDateProvider.current
    val utc = ZoneOffset.UTC
    val minMillis = minDate?.atStartOfDay(utc)?.toInstant()?.toEpochMilli()
    val requestedInit = initialDate ?: minDate ?: dp.today()
    val init = if (minDate != null && requestedInit.isBefore(minDate)) minDate else requestedInit
    val selectionMillis = init.atStartOfDay(utc).toInstant().toEpochMilli()

    val builder = MaterialDatePicker.Builder.datePicker()
        .setTheme(R.style.MyMaterialDatePickerTheme)
        .setSelection(selectionMillis)

    minMillis?.let { start ->
        builder.setCalendarConstraints(
            CalendarConstraints.Builder()
                .setStart(start)
                .setValidator(DateValidatorPointForward.from(start))
                .build()
        )
    }

    val datePicker = builder
        .build()

    datePicker.addOnPositiveButtonClickListener { timestamp ->
        onDateSelected(Instant.ofEpochMilli(timestamp).atZone(utc).toLocalDate())
    }

    val activity = context.findActivity()
    if (activity is ComponentActivity) {
        val fragmentManager = (activity as FragmentActivity).supportFragmentManager
        datePicker.show(fragmentManager, "DATE_PICKER")
    }
}

// Extension function to find activity from context (if you don't already have this)
fun Context.findActivity(): ComponentActivity? {
    var context = this
    while (context is android.content.ContextWrapper) {
        if (context is ComponentActivity) return context
        context = context.baseContext
    }
    return null
}
