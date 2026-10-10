package com.taskfree.app.data.backup

import com.taskfree.app.R
import com.taskfree.app.data.repository.BackupManager.BackupValidationException
import com.taskfree.app.enc.DatabaseKeyManager
import com.taskfree.app.testutil.assertFails
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class BackupCryptoTest {

    private val phrase = listOf("apple", "brick", "cloud", "delta", "eagle", "flame", "grape", "house")
    private val plain = """{"version":"1.0"}""".toByteArray()
    private val key = BackupCrypto.deriveKey(phrase, iterations = 1_000)

    private fun headerError(bytes: ByteArray) =
        assertFails<BackupValidationException> { BackupCrypto.checkHeader(bytes) }.resId

    @Test
    fun `encrypted backups decrypt with their phrase`() {
        val file = BackupCrypto.encrypt(plain, key)

        assertTrue(BackupCrypto.isEncrypted(file))
        assertArrayEquals(plain, BackupCrypto.decrypt(file, phrase))
        // Entered phrases are compared trimmed and lowercased.
        assertArrayEquals(plain, BackupCrypto.decrypt(file, phrase.map { " ${it.uppercase()} " }))
    }

    @Test
    fun `a wrong phrase or a changed byte does not unlock the file`() {
        val file = BackupCrypto.encrypt(plain, key)

        assertNull(BackupCrypto.decrypt(file, phrase.reversed()))
        for (index in listOf(30, file.size - 1)) { // a nonce byte in the header, then the tag
            val changed = file.copyOf().also { it[index] = (it[index] + 1).toByte() }
            assertNull(BackupCrypto.decrypt(changed, phrase))
        }
    }

    @Test
    fun `every encryption differs even for the same key and data`() {
        assertFalse(BackupCrypto.encrypt(plain, key).contentEquals(BackupCrypto.encrypt(plain, key)))
    }

    @Test
    fun `the backup key differs from the database key for the same phrase`() {
        val databaseKey = DatabaseKeyManager.deriveKeyFromPhrase(phrase)

        assertFalse(databaseKey.contentEquals(BackupCrypto.deriveKey(phrase).secret.encoded))
    }

    @Test
    fun `files cut short are reported as incomplete`() {
        val file = BackupCrypto.encrypt(plain, key)

        assertEquals(R.string.err_backup_incomplete, headerError(file.copyOf(file.size - 1)))
        assertEquals(R.string.err_backup_incomplete, headerError(file.copyOf(10)))
    }

    @Test
    fun `unknown versions and iteration counts are rejected`() {
        val file = BackupCrypto.encrypt(plain, key)

        val newVersion = file.copyOf().also { it[4] = 2 }
        assertEquals(R.string.err_backup_version, headerError(newVersion))
        for (iterations in listOf(0, 10_000_001)) {
            val bad = file.copyOf().also { ByteBuffer.wrap(it).putInt(6, iterations) }
            assertEquals(R.string.err_backup_version, headerError(bad))
        }
    }

    @Test
    fun `files without the magic are not backups`() {
        assertFalse(BackupCrypto.isEncrypted("{}".toByteArray()))
        assertEquals(R.string.err_not_a_backup, headerError("{}".toByteArray()))
    }
}
