// ui/enc/MnemonicManagerTest.kt
package com.taskfree.app.ui.enc

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskfree.app.Prefs
import com.taskfree.app.enc.DatabaseKeyManager
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MnemonicManagerTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() = DatabaseKeyManager.clearCachedKey()

    @Test
    fun `generates eight distinct dictionary words once`() {
        val phrase = MnemonicManager.getOrCreatePhrase(ctx)

        assertEquals(8, phrase.size)
        assertEquals(8, phrase.toSet().size)
        assertTrue(fetchWords().containsAll(phrase))
        assertEquals(phrase, MnemonicManager.getOrCreatePhrase(ctx))
        assertEquals(phrase, Prefs.loadPhrase(ctx))
    }

    @Test
    fun `validates against the stored hash only`() {
        val phrase = MnemonicManager.getOrCreatePhrase(ctx)

        assertTrue(MnemonicManager.isPhraseValid(ctx, phrase))
        assertTrue(MnemonicManager.isPhraseValid(ctx, phrase.map { it.uppercase() }))
        assertFalse(MnemonicManager.isPhraseValid(ctx, phrase.reversed()))
        // The phrase is random, so pick a replacement that is guaranteed to differ
        val otherWord = fetchWords().first { it != phrase.last() }
        assertFalse(MnemonicManager.isPhraseValid(ctx, phrase.dropLast(1) + otherWord))
    }

    @Test
    fun `nothing validates before a phrase exists`() {
        assertFalse(MnemonicManager.isPhraseValid(ctx, fetchWords().take(8)))
        assertFalse(MnemonicManager.hasKey(ctx))
    }

    @Test
    fun `storing a phrase derives, saves and caches its key`() {
        val phrase = fetchWords().takeLast(8)

        MnemonicManager.storePhrase(ctx, phrase)

        val expected = DatabaseKeyManager.deriveKeyFromPhrase(phrase)
        assertArrayEquals(expected, DatabaseKeyManager.getCachedKey())
        assertArrayEquals(expected, DatabaseKeyManager.loadDerivedKey(ctx))
        assertTrue(MnemonicManager.hasKey(ctx))
        assertTrue(MnemonicManager.isPhraseValid(ctx, phrase))
        assertEquals(phrase, Prefs.loadPhrase(ctx))
    }
}
