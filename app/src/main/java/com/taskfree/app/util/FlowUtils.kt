package com.taskfree.app.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

fun <T> Flow<T>.asStateFlow(scope: CoroutineScope, initial: T): StateFlow<T> =
    this.stateIn(scope, SharingStarted.WhileSubscribed(5000), initial)