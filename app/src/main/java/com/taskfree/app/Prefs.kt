package com.taskfree.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64

object Prefs {
    /* Drive-BACKED prefs (allowed in backup) */
    private const val FLAGS_FILE = "settings_flags"

    /* Drive-EXCLUDED prefs (secret) – add <exclude> rule in XML */
    private const val SECRET_FILE = "encryption_secret"

    private const val KEY_ENCRYPTED = "encrypted"
    private const val KEY_IN_PROGRESS = "migrationInProgress"
    private const val KEY_START_EPOCH = "migrationStartEpoch"
    private const val KEY_PHRASE = "phraseWords"
    private const val KEY_PHRASE_HASH = "phraseHash"   // Hash persists in backup for validation
    private const val KEY_DERIVED_KEY = "derived_key"

    /* helpers */
    private fun Context.flags() = getSharedPreferences(FLAGS_FILE, Context.MODE_PRIVATE)
    private fun Context.secret() = getSharedPreferences(SECRET_FILE, Context.MODE_PRIVATE)

    private inline fun SharedPreferences.edit(block: SharedPreferences.Editor.() -> Unit) =
        edit().apply(block).apply()

    private fun SharedPreferences.putBytes(key: String, data: ByteArray) =
        edit { putString(key, Base64.encodeToString(data, Base64.NO_WRAP)) }

    private fun SharedPreferences.getBytes(key: String): ByteArray? =
        getString(key, null)?.let { Base64.decode(it, Base64.NO_WRAP) }


    /* flags */
    fun isEncrypted(c: Context) = c.flags().getBoolean(KEY_ENCRYPTED, false)
    fun setEncrypted(c: Context, v: Boolean) = c.flags().edit { putBoolean(KEY_ENCRYPTED, v) }

    /* phrase – stored only in secret prefs, excluded from backup */
    fun savePhrase(c: Context, phrase: List<String>) =
        c.secret().edit { putString(KEY_PHRASE, phrase.joinToString(" ")) }

    fun loadPhrase(c: Context): List<String>? {
        val p = c.secret()

        // --- try ordered-string form, but protect against the legacy set ---
        val stored: String? = try {
            p.getString(KEY_PHRASE, null)          // will throw on HashSet
        } catch (_: ClassCastException) {
            null                                   // fall through to legacy path
        }

        stored?.takeIf { it.isNotBlank() }?.let { return it.split(" ") }

        // --- legacy unordered set; migrate it once, then return in order ---
        p.getStringSet(KEY_PHRASE, null)?.toList()?.also {
            savePhrase(c, it)                      // rewrite in new format
        }?.let { return it }

        return null
    }

    /* phrase hash – stored in flags (backed up) for validation during restore */
    fun savePhraseHash(c: Context, hash: String) =
        c.flags().edit { putString(KEY_PHRASE_HASH, hash) }

    fun loadPhraseHash(c: Context): String? = c.flags().getString(KEY_PHRASE_HASH, null)

    fun clearEncryption(c: Context) {
        c.flags().edit {
            remove(KEY_ENCRYPTED)
            remove(KEY_IN_PROGRESS)
            remove(KEY_START_EPOCH)
            remove(KEY_PHRASE_HASH)
        }
        c.secret().edit { clear() }
    }

    fun saveDerivedKey(c: Context, key: ByteArray) = c.secret().putBytes(KEY_DERIVED_KEY, key)
    fun loadDerivedKey(c: Context): ByteArray? = c.secret().getBytes(KEY_DERIVED_KEY)

    fun clearEncryptionSecrets(c: Context) = c.secret().edit { clear() }

    fun logSavedPassphraseAndKey(context: Context) {
        android.util.Log.d("Prefs", "=== ENCRYPTION DEBUG INFO ===")

        // Log the saved passphrase
        val phrase = loadPhrase(context)
        if (phrase != null) {
            android.util.Log.d("Prefs", "Saved passphrase words: ${phrase.joinToString(", ")}")
            android.util.Log.d("Prefs", "Passphrase word count: ${phrase.size}")
        } else {
            android.util.Log.d("Prefs", "No saved passphrase found")
        }

        // Log the saved derived key
        val derivedKey = loadDerivedKey(context)
        if (derivedKey != null) {
            val keyBase64 = android.util.Base64.encodeToString(derivedKey, android.util.Base64.NO_WRAP)
            android.util.Log.d("Prefs", "Saved derived key (Base64): $keyBase64")
            android.util.Log.d("Prefs", "Derived key length: ${derivedKey.size} bytes")
        } else {
            android.util.Log.d("Prefs", "No saved derived key found")
        }

        // Log the phrase hash for comparison
        val phraseHash = loadPhraseHash(context)
        if (phraseHash != null) {
            android.util.Log.d("Prefs", "Saved phrase hash: $phraseHash")
        } else {
            android.util.Log.d("Prefs", "No saved phrase hash found")
        }

        // Log encryption status
        android.util.Log.d("Prefs", "Encryption enabled: ${isEncrypted(context)}")

        android.util.Log.d("Prefs", "=== END DEBUG INFO ===")
    }

}