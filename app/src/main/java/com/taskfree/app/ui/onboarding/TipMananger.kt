package com.taskfree.app.ui.onboarding

import android.content.Context
import com.taskfree.app.data.preferences.TipPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*

class TipManager(context: Context) {

    private val tipPrefs = TipPreferences(context)
    private val queue = ArrayDeque<OnboardingTip>()
    private val _resetTick = MutableStateFlow(0)
    val resetTick: StateFlow<Int> = _resetTick.asStateFlow()

    private val _currentTip = MutableStateFlow<OnboardingTip?>(null)
    val currentTip: StateFlow<OnboardingTip?> = _currentTip.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main)

    fun request(tip: OnboardingTip, overrideSeen: Boolean = false) {
        scope.launch {
            val alreadySeen = tipPrefs.isSeen(tip.id)
            if (overrideSeen || !alreadySeen) {
                if (_currentTip.value == null) _currentTip.value = tip
                else queue.add(tip)
            }
        }
    }

    fun dismiss() {
        scope.launch {
            _currentTip.value?.let { tipPrefs.markSeen(it.id) }
            _currentTip.value = queue.poll()
        }
    }
    suspend fun hasSeen(id: TipId): Boolean = tipPrefs.isSeen(id)

    fun resetTips() {
        scope.launch {
            tipPrefs.clearAll()
            queue.clear()
            _currentTip.value = null
            _resetTick.update { it + 1 }
        }
    }
}
