// enc/DatabaseKeyManagerTest.kt
package com.taskfree.app.enc

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DatabaseKeyManagerTest {

    private val phrase = listOf(
        "abandon", "ability", "able", "about", "above", "absent", "absorb", "abstract"
    )

    @After
    fun tearDown() = DatabaseKeyManager.clearCachedKey()

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    /**
     * Existing encrypted databases can only be opened if this derivation never changes
     * (salt, iterations, key length, word separator). Expected value computed independently:
     * openssl kdf -keylen 32 -kdfopt digest:SHA256 -kdfopt "pass:<phrase>"
     *   -kdfopt salt:TaskAppEncryption -kdfopt iter:100000 PBKDF2
     */
    @Test
    fun `derived key matches the golden value`() {
        assertEquals(
            "405198609c027871feca96645a85819c2738e3658b33bbf229c63d654cf928c6",
            DatabaseKeyManager.deriveKeyFromPhrase(phrase).hex()
        )
    }

    @Test
    fun `derivation is deterministic and 256 bits`() {
        val a = DatabaseKeyManager.deriveKeyFromPhrase(phrase)
        val b = DatabaseKeyManager.deriveKeyFromPhrase(phrase.toList())
        assertEquals(32, a.size)
        assertArrayEquals(a, b)
    }

    @Test
    fun `word order matters`() {
        val a = DatabaseKeyManager.deriveKeyFromPhrase(phrase)
        val b = DatabaseKeyManager.deriveKeyFromPhrase(phrase.reversed())
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `cached key is copied in and out`() {
        val key = ByteArray(32) { it.toByte() }
        DatabaseKeyManager.cacheKey(key)
        key.fill(0)   // caller wipes its copy

        val cached = DatabaseKeyManager.getCachedKey()!!
        assertEquals(1.toByte(), cached[1])

        cached.fill(9)   // mutating the returned copy must not affect the cache
        assertEquals(1.toByte(), DatabaseKeyManager.getCachedKey()!![1])
    }

    @Test
    fun `clearing the cache forgets the key`() {
        DatabaseKeyManager.cacheKey(ByteArray(32) { 1 })
        DatabaseKeyManager.clearCachedKey()
        assertNull(DatabaseKeyManager.getCachedKey())
    }
}
