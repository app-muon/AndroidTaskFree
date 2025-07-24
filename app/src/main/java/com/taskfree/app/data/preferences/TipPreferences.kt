package com.taskfree.app.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.taskfree.app.ui.onboarding.TipId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.catch
import java.io.IOException

private val Context.dataStore by preferencesDataStore(name = "onboarding_tips")

class TipPreferences(private val context: Context) {

    suspend fun isSeen(tipId: TipId): Boolean {
        val key = booleanPreferencesKey(tipId.name)
        val prefs = context.dataStore.data
            .catch { if (it is IOException) emit(emptyPreferences()) }
            .first()

        return prefs[key] == true
    }

    suspend fun markSeen(tipId: TipId) {
        val key = booleanPreferencesKey(tipId.name)
        context.dataStore.edit { it[key] = true }
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }
}
