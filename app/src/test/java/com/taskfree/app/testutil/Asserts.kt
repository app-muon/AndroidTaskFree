// testutil/Asserts.kt
package com.taskfree.app.testutil

/**
 * Like JUnit's assertThrows, but inline so the block may call suspend functions
 * when used inside runTest.
 */
inline fun <reified T : Throwable> assertFails(block: () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        throw AssertionError("Expected ${T::class.simpleName} but got $e", e)
    }
    throw AssertionError("Expected ${T::class.simpleName} but nothing was thrown")
}
