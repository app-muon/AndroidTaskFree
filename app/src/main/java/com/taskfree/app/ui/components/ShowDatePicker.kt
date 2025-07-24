// showDatePicker.kt
package com.taskfree.app.ui.components

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.fragment.app.FragmentActivity
import com.taskfree.app.R
import com.google.android.material.datepicker.MaterialDatePicker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Shows a Material Date Picker with the custom theme
 * @param context The context to show the picker in
 * @param initialDate The initial date to select (defaults to today if null)
 * @param onDateSelected Callback when a date is selected
 */
fun showDatePicker(
    context: Context,
    initialDate: LocalDate? = null,
    onDateSelected: (LocalDate) -> Unit
) {
    val init = initialDate ?: LocalDate.now()
    val datePicker = MaterialDatePicker.Builder.datePicker()
        .setTheme(R.style.MyMaterialDatePickerTheme)
        .setSelection(init.toEpochDay() * 24 * 60 * 60 * 1000)
        .build()

    datePicker.addOnPositiveButtonClickListener { timestamp ->
        val pickedDate = Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        onDateSelected(pickedDate)
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