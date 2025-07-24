package com.taskfree.app.enc

import android.content.Context
import com.taskfree.app.Prefs
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object DatabaseKeyManager {
    private const val ITERATIONS = 100000
    private const val KEY_LENGTH = 256
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private val FIXED_SALT = "TaskAppEncryption".toByteArray()

    private var cachedKey: ByteArray? = null

    fun deriveKeyFromPhrase(phrase: List<String>): ByteArray {
        val password = phrase.joinToString(" ").toCharArray()

        val spec = PBEKeySpec(password, FIXED_SALT, ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance(ALGORITHM)
        val key = factory.generateSecret(spec).encoded

        // Clear password from memory
        password.fill('0')
        spec.clearPassword()

        return key
    }

    fun cacheKey(key: ByteArray) {
        cachedKey = key.copyOf()
    }

    fun getCachedKey(): ByteArray? = cachedKey?.copyOf()

    fun clearCachedKey() {
        cachedKey?.fill(0)
        cachedKey = null
    }


    fun loadDerivedKey(context: Context): ByteArray? = Prefs.loadDerivedKey(context)

    fun hasStoredKey(context: Context): Boolean {
        return Prefs.loadDerivedKey(context) != null
    }

    fun storeKey(context: Context, key: ByteArray) {
        Prefs.saveDerivedKey(context, key)
    }

    fun logCurrentKey() {
        val cached = getCachedKey()
        if (cached != null) {
            val keyBase64 = android.util.Base64.encodeToString(cached, android.util.Base64.NO_WRAP)
            android.util.Log.d("DatabaseKeyManager", "Cached key (Base64): $keyBase64")
            android.util.Log.d("DatabaseKeyManager", "Cached key length: ${cached.size} bytes")
        } else {
            android.util.Log.d("DatabaseKeyManager", "No cached key found")
        }
    }
}