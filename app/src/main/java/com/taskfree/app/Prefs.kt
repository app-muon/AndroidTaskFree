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
    private const val KEY_PENDING = "pendingEncryption"
    private const val KEY_ATTEMPT = "encryptionAttempt"
    private const val KEY_OUTCOME = "encryptionOutcome"

    internal enum class EncryptionOutcome { ROLLED_BACK, BLOCKED, CLEANUP_WARNING }

    // Operational keys live in runtime_state, which is excluded from cloud backup and device transfer.
    private val LEGACY_KEYS = listOf("legacySettingsSaved", "legacyEncrypted", "legacyKey", "legacyPhrase", "legacyHash")

    internal fun encryptionPending(c: Context) = c.runtime().getBoolean(KEY_PENDING, false)
    internal fun attemptStarted(c: Context) = c.runtime().getBoolean(KEY_ATTEMPT, false)
    /** Unknown or malformed values (e.g. after a downgrade) read as no outcome. */
    internal fun encryptionOutcome(c: Context): EncryptionOutcome? = runCatching {
        c.runtime().getString(KEY_OUTCOME, null)?.let(EncryptionOutcome::valueOf)
    }.getOrNull()

    internal fun requestEncryption(c: Context, phrase: List<String>,
        commit: (String, SharedPreferences.Editor) -> Boolean = { _, editor -> editor.commit() }) {
        check(!isEncrypted(c)) { "Database is already encrypted" }
        // Phrase first: a saved phrase without a pending request is harmless.
        check(commit("request_phrase", c.secret().edit().putString(KEY_PHRASE, phrase.joinToString(" ")))) {
            "Could not preserve recovery phrase"
        }
        check(commit("request", c.runtime().edit().putBoolean(KEY_PENDING, true).remove(KEY_OUTCOME))) {
            "Could not preserve pending encryption request"
        }
    }

    internal fun startEncryptionAttempt(c: Context, commit: (String, SharedPreferences.Editor) -> Boolean) {
        check(commit("attempt", c.runtime().edit().putBoolean(KEY_ATTEMPT, true))) {
            "Could not persist encryption attempt"
        }
    }

    internal fun finishEncryptionAttempt(
        c: Context, outcome: EncryptionOutcome?,
        commit: (String, SharedPreferences.Editor) -> Boolean = { _, editor -> editor.commit() }
    ) {
        val editor = c.runtime().edit().putString(KEY_OUTCOME, outcome?.name)
        if (outcome != EncryptionOutcome.BLOCKED) editor.remove(KEY_PENDING).remove(KEY_ATTEMPT)
        check(commit("outcome", editor)) { "Could not persist encryption outcome" }
    }

    internal fun acknowledgeEncryptionOutcome(c: Context,
        commit: (String, SharedPreferences.Editor) -> Boolean = { _, editor -> editor.commit() }) {
        check(commit("acknowledge", c.runtime().edit().remove(KEY_OUTCOME))) { "Could not acknowledge encryption outcome" }
    }

    // Operational state must not follow a backup onto another installation.
    private fun Context.runtime() = getSharedPreferences("runtime_state", Context.MODE_PRIVATE)

    internal fun recordAutomaticRestart(c: Context, now: Long,
        commit: (String, SharedPreferences.Editor) -> Boolean): Boolean {
        val prefs = c.runtime()
        if (prefs.contains("automaticRestart")) {
            val previous = prefs.getLong("automaticRestart", 0)
            if (previous > now || now - previous < 10_000) return false
        }
        return commit("automatic_restart", prefs.edit().putLong("automaticRestart", now))
    }

    internal fun reminderNoticePosted(c: Context) = c.runtime().getBoolean("reminderNotice", false)
    internal fun setReminderNoticePosted(c: Context, posted: Boolean) {
        check(c.runtime().edit().putBoolean("reminderNotice", posted).commit()) {
            "Could not persist reminder notice"
        }
    }

    internal fun preserveLegacySettings(c: Context) {
        if (c.runtime().getBoolean("legacySettingsSaved", false)) return
        check(c.runtime().edit().putBoolean("legacySettingsSaved", true)
            .putBoolean("legacyEncrypted", isEncrypted(c))
            .putString("legacyKey", c.secret().getString(KEY_DERIVED_KEY, null))
            .putString("legacyPhrase", loadPhrase(c)?.joinToString(" "))
            .putString("legacyHash", loadPhraseHash(c)).commit())
    }

    internal fun restoreLegacySettings(c: Context) {
        val saved = c.runtime()
        if (!saved.getBoolean("legacySettingsSaved", false)) return
        check(c.flags().edit().putBoolean(KEY_ENCRYPTED, saved.getBoolean("legacyEncrypted", false))
            .putString(KEY_PHRASE_HASH, saved.getString("legacyHash", null)).commit())
        check(c.secret().edit().putString(KEY_DERIVED_KEY, saved.getString("legacyKey", null))
            .putString(KEY_PHRASE, saved.getString("legacyPhrase", null)).commit())
        forgetLegacySettings(c)
        com.taskfree.app.enc.DatabaseKeyManager.clearCachedKey()
    }

    internal fun forgetLegacySettings(c: Context) {
        check(c.runtime().edit().apply { LEGACY_KEYS.forEach { remove(it) } }.commit())
    }

    internal fun commitSelectedDatabase(c: Context, key: ByteArray?) {
        val phrase = loadPhrase(c)?.takeIf {
            key != null && com.taskfree.app.enc.DatabaseKeyManager.deriveKeyFromPhrase(it).contentEquals(key)
        }
        val secrets = c.secret().edit().putString(KEY_DERIVED_KEY,
            key?.let { Base64.encodeToString(it, Base64.NO_WRAP) })
        // Do not display an unrelated phrase as recovery for the selected encrypted file.
        if (key != null && phrase == null) secrets.remove(KEY_PHRASE)
        check(secrets.commit())
        check(c.flags().edit().putBoolean(KEY_ENCRYPTED, key != null).putString(KEY_PHRASE_HASH,
            phrase?.let(com.taskfree.app.ui.enc.MnemonicManager::hashPhrase)).commit())
        com.taskfree.app.enc.DatabaseKeyManager.clearCachedKey()
    }

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

    /**
     * Keeps the phrase that unlocked a restored backup file, so later backups use the phrase the
     * user already has. Only for a phone with no phrase and no encrypted database. Committed,
     * because the app restarts straight after a restore.
     */
    internal fun keepRestoredPhrase(c: Context, phrase: List<String>) {
        if (isEncrypted(c) || loadPhrase(c) != null) return
        check(c.secret().edit().putString(KEY_PHRASE, phrase.joinToString(" ")).commit()) {
            "Could not keep recovery phrase"
        }
        check(c.flags().edit().putString(KEY_PHRASE_HASH,
            com.taskfree.app.ui.enc.MnemonicManager.hashPhrase(phrase)).commit()) { "Could not keep phrase hash" }
    }

    internal fun commitMigrationPhrase(
        c: Context, phrase: List<String>, commit: (String, SharedPreferences.Editor) -> Boolean
    ) {
        check(commit("phrase", c.secret().edit().putString(KEY_PHRASE, phrase.joinToString(" ")))) {
            "Could not preserve recovery phrase"
        }
    }

    internal fun commitEncryptedMigrationState(
        c: Context, phrase: List<String>, key: ByteArray,
        commit: (String, SharedPreferences.Editor) -> Boolean
    ) {
        val hash = com.taskfree.app.ui.enc.MnemonicManager.hashPhrase(phrase)
        check(commit("secrets", c.secret().edit()
            .putString(KEY_PHRASE, phrase.joinToString(" "))
            .putString(KEY_DERIVED_KEY, Base64.encodeToString(key, Base64.NO_WRAP)))) {
            "Could not persist encryption key and phrase"
        }
        check(commit("flags", c.flags().edit()
            .putString(KEY_PHRASE_HASH, hash).putBoolean(KEY_ENCRYPTED, true))) {
            "Could not persist encryption flag"
        }
    }

    internal fun commitPlaintextMigrationState(
        c: Context, commit: (String, SharedPreferences.Editor) -> Boolean
    ) {
        check(commit("plaintext_flags", c.flags().edit()
            .putBoolean(KEY_ENCRYPTED, false).remove(KEY_PHRASE_HASH)
            .remove(KEY_IN_PROGRESS).remove(KEY_START_EPOCH))) { "Could not restore plaintext flag" }
        // Retain the phrase for retry; a migration key must never survive rollback.
        check(commit("plaintext_secrets", c.secret().edit().remove(KEY_DERIVED_KEY))) {
            "Could not clear migration key"
        }
    }

    fun clearEncryption(c: Context) {
        check(c.flags().edit().apply {
            remove(KEY_ENCRYPTED)
            remove(KEY_IN_PROGRESS)
            remove(KEY_START_EPOCH)
            remove(KEY_PHRASE_HASH)
        }.commit()) { "Could not reset encryption flags" }
        check(c.secret().edit().clear().commit()) { "Could not reset encryption secrets" }
        // Keep the restart guard and reminder-notice flag; they are not encryption state.
        check(c.runtime().edit().apply {
            (listOf(KEY_PENDING, KEY_ATTEMPT, KEY_OUTCOME) + LEGACY_KEYS).forEach { remove(it) }
        }.commit()) { "Could not reset encryption progress" }
    }

    fun saveDerivedKey(c: Context, key: ByteArray) = c.secret().putBytes(KEY_DERIVED_KEY, key)
    fun loadDerivedKey(c: Context): ByteArray? = c.secret().getBytes(KEY_DERIVED_KEY)

    fun clearEncryptionSecrets(c: Context) = c.secret().edit { clear() }


}
