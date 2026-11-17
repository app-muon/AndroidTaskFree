package com.taskfree.app.ui

import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.taskfree.app.MainActivity
import com.taskfree.app.R
import com.taskfree.app.ui.task.activity
import kotlinx.coroutines.CancellationException

/** Each destination handles the shared request only while resumed and fully restored. */
@Composable
internal fun NotificationNavigation(
    ready: Boolean,
    modalOpen: Boolean,
    openTaskId: Int? = null,
    onOpen: suspend (Int, () -> Boolean) -> Boolean?
) {
    val activity = LocalContext.current.activity() as? MainActivity ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val taskId = activity.notificationTaskId
    val requestId = activity.notificationRequestId
    val currentReady by rememberUpdatedState(ready)
    val currentModalOpen by rememberUpdatedState(modalOpen)
    val currentOpen by rememberUpdatedState(onOpen)
    LaunchedEffect(taskId, requestId, ready, modalOpen, openTaskId, lifecycleState) {
        if (taskId == null || !ready || lifecycleState != Lifecycle.State.RESUMED) return@LaunchedEffect
        if (taskId == openTaskId) {
            activity.consumeNotification(taskId, requestId)
            return@LaunchedEffect
        }
        if (modalOpen) {
            activity.showDeferredNotificationNotice(requestId)
            return@LaunchedEffect
        }
        // Recheck after database suspension: another tap or editor may have arrived meanwhile.
        val canOpen = {
            currentReady && !currentModalOpen && lifecycle.currentState == Lifecycle.State.RESUMED &&
                activity.notificationTaskId == taskId && activity.notificationRequestId == requestId
        }
        try {
            val opened = currentOpen(taskId, canOpen)
            if (opened != null) {
                if (!opened && canOpen()) {
                    Toast.makeText(activity, R.string.repeat_task_missing, Toast.LENGTH_SHORT).show()
                }
                activity.consumeNotification(taskId, requestId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("NotificationNavigation", "Could not open task $taskId", e)
            Toast.makeText(activity, R.string.notification_open_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
