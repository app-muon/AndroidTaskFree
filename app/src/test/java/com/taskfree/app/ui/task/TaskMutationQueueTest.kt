package com.taskfree.app.ui.task

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskMutationQueueTest {
    @Test fun `consumer awaits suspended operations in submission order and survives a failure`() = runTest {
        val calls = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val queue = TaskMutationQueue(backgroundScope) { calls += "failed" }
        queue.submit {
            calls += "start"
            gate.await()
            calls += "commit"
        }
        queue.submit { calls += "next"; error("injected") }
        val last = queue.submit { calls += "last" }
        runCurrent()
        assertEquals(listOf("start"), calls)
        assertFalse(last.isCompleted)
        gate.complete(Unit)
        last.join()
        assertEquals(listOf("start", "commit", "next", "failed", "last"), calls)
    }
}
