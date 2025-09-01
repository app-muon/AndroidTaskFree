// app/src/main/java/com/taskfree/app/settings/SettingsPrefs.kt
package com.taskfree.app.settings

import android.content.Context
import androidx.core.content.edit
import com.taskfree.app.ui.theme.TextScaleOption

object SettingsPrefs {
    private const val FILE = "com.taskfree.app.settings"
    private const val KEY_TEXT_SCALE = "pref_text_scale_option_v1"

    fun getTextScaleOption(ctx: Context): TextScaleOption {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_TEXT_SCALE, null)
        return TextScaleOption.fromPref(raw)
    }

    fun setTextScaleOption(ctx: Context, option: TextScaleOption) {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        sp.edit { putString(KEY_TEXT_SCALE, option.prefValue) } // async apply()
    }
}