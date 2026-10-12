package com.taskfree.app.data.backup

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class BackupPrefsTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun `slot metadata pending work and errors survive reload and clear together`() {
        val slots = listOf("A", "B").map {
            BackupSlot(Uri.parse("content://test/$it"), "TaskFree-$it.tfbackup", "digest-$it", 123)
        }
        val expected = BackupSettings(Uri.parse("content://test/tree/folder"), "phrase hash", slots,
            activeSlot = 1, fingerprint = "content hash", savedAt = Instant.ofEpochMilli(1234),
            error = BackupError(BackupError.Kind.FAILED, Instant.ofEpochMilli(3456), "close"), pending = true)
        assertTrue(BackupPrefs(ctx).commit(expected))
        assertEquals(expected, BackupPrefs(ctx).load())
        assertTrue(BackupPrefs(ctx).commit(null))
        assertNull(BackupPrefs(ctx).load())
    }

    @Test fun `old single document settings retain their saved status and require migration`() {
        ctx.getSharedPreferences("local_backup", Context.MODE_PRIVATE).edit().clear()
            .putString("uri", "content://test/legacy")
            .putString("fileName", "Old.tfbackup")
            .putString("phraseHash", "old hash")
            .putString("fingerprint", "old fingerprint")
            .putLong("savedAt", 1234).commit()
        val legacy = BackupPrefs(ctx).load()!!
        assertTrue(legacy.needsMigration)
        assertEquals("Old.tfbackup", legacy.fileName)
        assertEquals(Instant.ofEpochMilli(1234), legacy.savedAt)
        assertEquals("old fingerprint", legacy.fingerprint)
        assertTrue(BackupPrefs(ctx).commit(legacy))
        assertEquals(legacy, BackupPrefs(ctx).load())
    }
}
