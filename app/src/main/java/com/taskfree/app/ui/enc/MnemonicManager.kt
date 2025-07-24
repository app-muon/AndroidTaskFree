package com.taskfree.app.ui.enc

import android.content.Context
import android.util.Log
import com.taskfree.app.Prefs
import com.taskfree.app.enc.DatabaseKeyManager
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.random.asKotlinRandom

object MnemonicManager {

    /** Returns the cached phrase, or generates & stores a new one (8 words). */
    fun getOrCreatePhrase(context: Context): List<String> {
        Prefs.loadPhrase(context)?.let { return it }

        val wordlist = fetchWords()
        val rnd = SecureRandom().asKotlinRandom()
        val words = wordlist.shuffled(rnd).take(8)

        // Save both the phrase and its hash for validation
        Prefs.savePhrase(context, words)
        savePhraseHash(context, words)

        Log.d("MnemonicManager", "Generated phrase and derived key")
        return words
    }

    fun hasKey(context: Context): Boolean {
        return DatabaseKeyManager.hasStoredKey(context) ||
                DatabaseKeyManager.getCachedKey() != null
    }

    /**
     * Validates a phrase by checking if it matches the stored hash
     * This works even when the original phrase is cleared from local storage
     */
    fun isPhraseValid(context: Context, phrase: List<String>): Boolean {
        val storedHash = getPhraseHash(context)
        if (storedHash == null) {
            Log.w("MnemonicManager", "No stored phrase hash found")
            return false
        }

        val enteredHash = hashPhrase(phrase)
        val isValid = enteredHash == storedHash

        Log.d("MnemonicManager", "Phrase validation: $isValid")
        return isValid
    }

    /**
     * Restores the phrase after successful validation
     * This re-establishes the key for future use
     */
    fun storePhrase(context: Context, phrase: List<String>) {
        Prefs.savePhrase(context, phrase)
        savePhraseHash(context, phrase)
        // Derive and cache the encryption key
        val key = DatabaseKeyManager.deriveKeyFromPhrase(phrase)
        DatabaseKeyManager.storeKey(context, key)
        DatabaseKeyManager.cacheKey(key)

        Log.d("MnemonicManager", "Phrase restored and key cached")
    }

    private fun hashPhrase(phrase: List<String>): String {
        val combined = phrase.joinToString("|").lowercase()
        return MessageDigest.getInstance("SHA-256")
            .digest(combined.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun savePhraseHash(context: Context, phrase: List<String>) {
        val hash = hashPhrase(phrase)
        Prefs.savePhraseHash(context, hash)
    }

    private fun getPhraseHash(context: Context): String? {
        return Prefs.loadPhraseHash(context)
    }

}