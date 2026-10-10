package com.taskfree.app.data.backup

import com.taskfree.app.R
import com.taskfree.app.data.repository.BackupManager.BackupValidationException
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted backup file: a fixed header, then AES-256-GCM ciphertext of the backup JSON.
 * The header is authenticated as associated data, so any change to it fails decryption.
 *
 * Header (big-endian): magic "TFBK", format version, KDF id, iterations, salt, nonce,
 * ciphertext length. The length lets a file cut short (e.g. still syncing) be reported
 * as incomplete instead of as a wrong phrase.
 */
internal object BackupCrypto {
    const val ITERATIONS = 600_000
    private const val MAX_ITERATIONS = 10_000_000
    private val MAGIC = "TFBK".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 1
    private const val KDF_PBKDF2_SHA256: Byte = 1
    private const val SALT_SIZE = 16
    private const val NONCE_SIZE = 12
    private const val TAG_BITS = 128
    private const val HEADER_SIZE = 4 + 1 + 1 + 4 + SALT_SIZE + NONCE_SIZE + 8

    class Key(val salt: ByteArray, val iterations: Int, internal val secret: SecretKeySpec)

    /** Its own random salt keeps this key different from the database key derived from the same phrase. */
    fun deriveKey(
        phrase: List<String>,
        salt: ByteArray = randomBytes(SALT_SIZE),
        iterations: Int = ITERATIONS
    ): Key {
        val password = phrase.joinToString(" ") { it.trim().lowercase() }.toCharArray()
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return Key(salt.copyOf(), iterations, SecretKeySpec(bytes, "AES"))
        } finally {
            password.fill('0')
            spec.clearPassword()
        }
    }

    fun isEncrypted(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC.size && bytes.copyOf(MAGIC.size).contentEquals(MAGIC)

    fun encrypt(plain: ByteArray, key: Key): ByteArray {
        val nonce = randomBytes(NONCE_SIZE)
        val header = ByteBuffer.allocate(HEADER_SIZE)
            .put(MAGIC).put(VERSION).put(KDF_PBKDF2_SHA256).putInt(key.iterations)
            .put(key.salt).put(nonce).putLong(plain.size + TAG_BITS / 8L)
            .array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key.secret, GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(header)
        }
        return header + cipher.doFinal(plain)
    }

    /** Rejects files this version can't read, or that are cut short, before a phrase is asked for. */
    fun checkHeader(bytes: ByteArray) {
        parseHeader(bytes)
    }

    /** Returns null when [phrase] does not unlock the file. */
    fun decrypt(bytes: ByteArray, phrase: List<String>): ByteArray? {
        val header = parseHeader(bytes)
        val key = deriveKey(phrase, header.salt, header.iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key.secret, GCMParameterSpec(TAG_BITS, header.nonce))
            updateAAD(bytes, 0, HEADER_SIZE)
        }
        return try {
            cipher.doFinal(bytes, HEADER_SIZE, header.length)
        } catch (_: AEADBadTagException) {
            null
        }
    }

    private class Header(val iterations: Int, val salt: ByteArray, val nonce: ByteArray, val length: Int)

    private fun parseHeader(bytes: ByteArray): Header {
        if (!isEncrypted(bytes)) throw BackupValidationException(R.string.err_not_a_backup)
        if (bytes.size < HEADER_SIZE) throw BackupValidationException(R.string.err_backup_incomplete)
        val buffer = ByteBuffer.wrap(bytes, MAGIC.size, HEADER_SIZE - MAGIC.size)
        val version = buffer.get()
        val kdf = buffer.get()
        val iterations = buffer.getInt()
        if (version != VERSION || kdf != KDF_PBKDF2_SHA256 || iterations !in 1..MAX_ITERATIONS)
            throw BackupValidationException(R.string.err_backup_version)
        val salt = ByteArray(SALT_SIZE).also { buffer.get(it) }
        val nonce = ByteArray(NONCE_SIZE).also { buffer.get(it) }
        val length = buffer.getLong()
        if (length < TAG_BITS / 8 || length > bytes.size - HEADER_SIZE)
            throw BackupValidationException(R.string.err_backup_incomplete)
        return Header(iterations, salt, nonce, length.toInt())
    }

    private fun randomBytes(size: Int) = ByteArray(size).also { SecureRandom().nextBytes(it) }
}
