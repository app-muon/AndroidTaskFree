package com.taskfree.app.ui.task

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** One consumer awaits the entire operation, including suspensions, before starting the next. */
internal class TaskMutationQueue(scope: CoroutineScope, private val onFailure: (Exception) -> Unit) {
    private data class Command(val done: CompletableJob, val run: suspend () -> Unit)
    private val commands = Channel<Command>(Channel.UNLIMITED)

    init {
        scope.launch {
            try {
                for (command in commands) {
                    try {
                        command.run()
                        command.done.complete()
                    } catch (e: CancellationException) {
                        command.done.cancel(e)
                        throw e
                    } catch (e: Exception) {
                        onFailure(e)
                        command.done.complete()
                    }
                }
            } finally {
                commands.close()
                while (true) (commands.tryReceive().getOrNull() ?: break).done.cancel()
            }
        }
    }

    fun submit(block: suspend () -> Unit): Job {
        val done = Job()
        if (commands.trySend(Command(done, block)).isFailure) done.cancel()
        return done
    }
}
