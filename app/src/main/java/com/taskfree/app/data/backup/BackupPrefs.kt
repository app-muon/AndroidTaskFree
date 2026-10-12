package com.taskfree.app.data.backup

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.net.toUri
import java.time.Instant

internal data class BackupError(val kind: Kind, val at: Instant, val detail: String? = null) {
    enum class Kind {
        NO_ACCESS, PHRASE_CHANGED, FAILED, TOO_LARGE, ACTIVE_DAMAGED, SETTINGS_FAILED
    }
}

internal data class BackupSlot(
    val uri: Uri,
    val fileName: String,
    val digest: String? = null,
    val size: Long = 0
)

internal data class BackupSettings(
    /** Persisted tree grant, or the old single-document grant during migration. */
    val uri: Uri,
    val phraseHash: String,
    val slots: List<BackupSlot> = emptyList(),
    val activeSlot: Int = 0,
    val fingerprint: String? = null,
    val savedAt: Instant? = null,
    val error: BackupError? = null,
    val pending: Boolean = true,
    val legacyFileName: String = ""
) {
    val needsMigration: Boolean get() = slots.size != 2
    val active: BackupSlot? get() = slots.getOrNull(activeSlot)
    val fileName: String get() = active?.fileName ?: legacyFileName
    val inPhoneStorage: Boolean get() = uri.isPhoneStorage()
}

/** Phone storage avoids cloud providers whose writes cannot be read back immediately. */
internal fun Uri.isPhoneStorage(): Boolean =
    authority == "com.android.externalstorage.documents" ||
        authority == "com.android.providers.downloads.documents"

internal interface BackupSettingsStore {
    fun load(): BackupSettings?
    /** False (or an exception) leaves persistence uncertain. Stop writing until process restart. */
    fun commit(settings: BackupSettings?): Boolean
}

/** All slot URIs and verification metadata are excluded from Android backup and device transfer. */
internal class BackupPrefs(context: Context) : BackupSettingsStore {
    private val prefs = context.getSharedPreferences("local_backup", Context.MODE_PRIVATE)

    override fun load(): BackupSettings? {
        val folder = prefs.getString("folderUri", null)
        val uri = (folder ?: prefs.getString("uri", null))?.toUri() ?: return null
        val slots = if (folder == null) emptyList() else (0..1).mapNotNull { index ->
            prefs.getString("slot${index}Uri", null)?.let {
                BackupSlot(it.toUri(), prefs.getString("slot${index}Name", "").orEmpty(),
                    prefs.getString("slot${index}Digest", null), prefs.getLong("slot${index}Size", 0))
            }
        }
        val kind = prefs.getString("errorKind", null)
            ?.let { runCatching { BackupError.Kind.valueOf(it) }.getOrNull() }
        return BackupSettings(
            uri = uri,
            phraseHash = prefs.getString("phraseHash", null).orEmpty(),
            slots = slots,
            activeSlot = prefs.getInt("activeSlot", 0),
            fingerprint = prefs.getString("fingerprint", null),
            savedAt = prefs.instant("savedAt"),
            error = kind?.let {
                BackupError(it, prefs.instant("errorAt") ?: Instant.EPOCH, prefs.getString("errorDetail", null))
            },
            pending = prefs.getBoolean("pending", true),
            legacyFileName = prefs.getString("fileName", null).orEmpty()
        )
    }

    override fun commit(settings: BackupSettings?): Boolean = prefs.edit().apply {
        clear()
        settings?.let { s ->
            putString(if (s.needsMigration) "uri" else "folderUri", s.uri.toString())
            putString("fileName", s.legacyFileName)
            putString("phraseHash", s.phraseHash)
            s.slots.forEachIndexed { index, slot ->
                putString("slot${index}Uri", slot.uri.toString())
                putString("slot${index}Name", slot.fileName)
                putString("slot${index}Digest", slot.digest)
                putLong("slot${index}Size", slot.size)
            }
            putInt("activeSlot", s.activeSlot)
            putString("fingerprint", s.fingerprint)
            s.savedAt?.let { putLong("savedAt", it.toEpochMilli()) }
            putBoolean("pending", s.pending)
            s.error?.let {
                putString("errorKind", it.kind.name)
                putLong("errorAt", it.at.toEpochMilli())
                putString("errorDetail", it.detail)
            }
        }
    }.commit()

    private fun SharedPreferences.instant(key: String): Instant? =
        if (contains(key)) Instant.ofEpochMilli(getLong(key, 0)) else null
}
