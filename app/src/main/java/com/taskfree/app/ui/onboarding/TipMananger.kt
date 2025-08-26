package com.taskfree.app.ui.onboarding

import android.content.Context
import com.taskfree.app.data.preferences.TipPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Queues onboarding tips, guarantees no duplicates in a session,
 * and respects “already-seen” state stored in [TipPreferences].
 */
class TipManager(context: Context) {

    private val prefs = TipPreferences(context)
    private val queue = ArrayDeque<OnboardingTip>()
    private val inMemory = mutableSetOf<TipId>()               // de-dupe for this run
    private val mutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    /* ── State exposed to UI ─────────────────────────────────────────── */
    private val _current = MutableStateFlow<OnboardingTip?>(null)
    val currentTip: StateFlow<OnboardingTip?> = _current

    /* simple tick flow so callers can force recomposition if they reset */
    private val _resetTick = MutableStateFlow(0)
    val resetTick: StateFlow<Int> = _resetTick

    /**
     * Request a tip.
     * @param overrideSeen  show even if it was previously marked as seen.
     */
    fun request(tip: OnboardingTip, overrideSeen: Boolean = false) = scope.launch {
        // Considered duplicate if already current, queued, or shown this session.
        fun isDuplicate(): Boolean =
            (tip.id in inMemory) ||
                    (_current.value?.id == tip.id) ||
                    queue.any { it.id == tip.id }

        // 1) Fast duplicate snapshot under lock (always block duplicates).
        val dupAtStart = mutex.withLock { isDuplicate() }
        if (dupAtStart) return@launch

        // 2) Disk check off-lock (skip if overrideSeen).
        val alreadySeen = if (!overrideSeen) {
            withContext(Dispatchers.IO) { prefs.isSeen(tip.id) }
        } else false
        if (alreadySeen) return@launch

        // 3) Commit under lock with a second duplicate check to avoid TOCTOU.
        mutex.withLock {
            if (isDuplicate()) return@withLock

            // Remove any stale duplicate already in queue, then enqueue.
            queue.removeAll { it.id == tip.id }
            inMemory += tip.id

            if (_current.value == null) _current.value = tip
            else queue.addLast(tip)
        }
    }

    /** Mark current tip as seen and advance to the next queued tip. */
    fun dismiss() = scope.launch {
        mutex.withLock {
            _current.value?.let { tip ->
                withContext(Dispatchers.IO) { prefs.markSeen(tip.id) }
                inMemory -= tip.id
            }
            _current.value = queue.removeFirstOrNull()
        }
    }
    suspend fun hasSeen(id: TipId): Boolean = prefs.isSeen(id)

    /** Clear *all* stored “seen” flags and any tips in flight. */
    fun resetTips() = scope.launch {
        withContext(Dispatchers.IO) { prefs.clearAll() }

        mutex.withLock {
            inMemory.clear()
            queue.clear()
            _current.value = null
            _resetTick.update { it + 1 }      // tell the UI a reset happened
        }
    }
}

