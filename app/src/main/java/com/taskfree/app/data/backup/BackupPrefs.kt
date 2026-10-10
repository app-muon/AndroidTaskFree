package com.taskfree.app.data.backup

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import java.time.Instant

internal data class BackupError(val kind: Kind, val at: Instant, val detail: String? = null) {
    enum class Kind {
        /** The write grant is gone; the user must choose the file again. */
        NO_ACCESS,
        /** The phone's recovery phrase is missing or differs from the one backups use. */
        PHRASE_CHANGED,
        /** Retried automatically at the next save. */
        FAILED
    }
}

internal data class BackupSettings(
    val uri: Uri,
    val fileName: String,
    val phraseHash: String,
    val fingerprint: String?,
    val savedAt: Instant?,
    val error: BackupError?
) {
    val inPhoneStorage: Boolean get() = uri.isPhoneStorage()
}

/** Phone storage, where repeated saves are reliable; cloud apps' files can fail to save while idle. */
internal fun Uri.isPhoneStorage(): Boolean =
    authority == "com.android.externalstorage.documents" ||
        authority == "com.android.providers.downloads.documents"

/** Excluded from Android backup and device transfer: file grants don't carry over to another phone. */
internal class BackupPrefs(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Null when automatic backup is off. */
    fun load(): BackupSettings? {
        val uri = prefs.getString(KEY_URI, null)?.toUri() ?: return null
        val kind = prefs.getString(KEY_ERROR_KIND, null)
            ?.let { runCatching { BackupError.Kind.valueOf(it) }.getOrNull() }
        return BackupSettings(
            uri = uri,
            fileName = prefs.getString(KEY_FILE_NAME, null).orEmpty(),
            phraseHash = prefs.getString(KEY_PHRASE_HASH, null).orEmpty(),
            fingerprint = prefs.getString(KEY_FINGERPRINT, null),
            savedAt = prefs.instant(KEY_SAVED_AT),
            error = kind?.let {
                BackupError(it, prefs.instant(KEY_ERROR_AT) ?: Instant.EPOCH, prefs.getString(KEY_ERROR_DETAIL, null))
            }
        )
    }

    /** Starts afresh with a new file. */
    fun enable(uri: Uri, fileName: String, phraseHash: String) = prefs.edit {
        clear()
        putString(KEY_URI, uri.toString())
        putString(KEY_FILE_NAME, fileName)
        putString(KEY_PHRASE_HASH, phraseHash)
    }

    /** Clears the fingerprint so the next save writes with the new phrase. */
    fun usePhrase(phraseHash: String) = prefs.edit {
        putString(KEY_PHRASE_HASH, phraseHash)
        remove(KEY_FINGERPRINT)
    }

    fun recordSuccess(fingerprint: String, at: Instant) = prefs.edit {
        putString(KEY_FINGERPRINT, fingerprint)
        putLong(KEY_SAVED_AT, at.toEpochMilli())
        remove(KEY_ERROR_KIND)
        remove(KEY_ERROR_AT)
        remove(KEY_ERROR_DETAIL)
    }

    fun recordError(error: BackupError) = prefs.edit {
        putString(KEY_ERROR_KIND, error.kind.name)
        putLong(KEY_ERROR_AT, error.at.toEpochMilli())
        putString(KEY_ERROR_DETAIL, error.detail)
    }

    fun clear() = prefs.edit { clear() }

    private fun SharedPreferences.instant(key: String): Instant? =
        if (contains(key)) Instant.ofEpochMilli(getLong(key, 0)) else null

    private companion object {
        const val FILE = "local_backup"
        const val KEY_URI = "uri"
        const val KEY_FILE_NAME = "fileName"
        const val KEY_PHRASE_HASH = "phraseHash"
        const val KEY_FINGERPRINT = "fingerprint"
        const val KEY_SAVED_AT = "savedAt"
        const val KEY_ERROR_KIND = "errorKind"
        const val KEY_ERROR_AT = "errorAt"
        const val KEY_ERROR_DETAIL = "errorDetail"
    }
}
