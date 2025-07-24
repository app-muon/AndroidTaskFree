// showTimePicker.kt
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.fragment.app.FragmentActivity
import com.taskfree.app.R
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.time.LocalTime

fun showTimePicker(
    context: Context,
    initial: LocalTime? = null,
    onTimeSelected: (LocalTime) -> Unit
) {
    val picker = MaterialTimePicker.Builder()
        .setTheme(R.style.MyMaterialTimePickerTheme)
        .setTimeFormat(TimeFormat.CLOCK_24H)
        .setHour(initial?.hour ?: 9)
        .setMinute(initial?.minute ?: 0)
        .build()

    picker.addOnPositiveButtonClickListener {
        onTimeSelected(LocalTime.of(picker.hour, picker.minute))
    }

    val activity = context.findActivity()
    if (activity is ComponentActivity) {
        val fragmentManager = (activity as FragmentActivity).supportFragmentManager
        picker.show(fragmentManager, "TIME_PICKER")
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